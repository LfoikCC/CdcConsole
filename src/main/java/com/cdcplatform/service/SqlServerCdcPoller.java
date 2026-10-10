package com.cdcplatform.service;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.Types;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.DeliveryBatchRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class SqlServerCdcPoller {
    private static final Logger log = LoggerFactory.getLogger(SqlServerCdcPoller.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final ConfigRepository repository;
    private final ConnectionProbeService connections;
    private final EventDispatchService dispatch;
    private final ObjectMapper json;
    private final TaskRepository tasks;
    private final DeliveryBatchRepository deliveryBatches;
    private final DeliveryService delivery;
    private final CaptureGroupService captureGroups;
    private final Map<Long, Long> nextPollAt = new ConcurrentHashMap<>();
    private final Map<Long, PollStatus> statuses = new ConcurrentHashMap<>();
    private final Map<Long, MySqlWorker> mysqlWorkers = new ConcurrentHashMap<>();

    public SqlServerCdcPoller(ConfigRepository repository, ConnectionProbeService connections, EventDispatchService dispatch,
                              ObjectMapper json, TaskRepository tasks, DeliveryBatchRepository deliveryBatches,
                              DeliveryService delivery, CaptureGroupService captureGroups) {
        this.repository = repository; this.connections = connections; this.dispatch = dispatch; this.json = json;
        this.tasks = tasks;
        this.deliveryBatches = deliveryBatches; this.delivery = delivery; this.captureGroups = captureGroups;
    }

    @Scheduled(fixedDelayString = "${cdc.poller.tick-ms:100}")
    public void tick() {
        long now = System.currentTimeMillis();
        var mappings = repository.mappings();
        var activeIds = new HashSet<Long>();
        var polledGroups = new HashSet<Long>();
        for (var mapping : mappings) {
            if (!mapping.enabled()) continue;
            try {
                if (delivery.hasPending(mapping.id())) { delivery.trigger(mapping.id()); continue; }
                var source = repository.source(mapping.sourceId(), true);
                if (!source.enabled()) continue;
                if (tasks.taskIdForMapping(mapping.id()) != null && source.dbType() != Types.DatabaseType.STARROCKS)
                    continue;
                if (source.dbType() == Types.DatabaseType.MYSQL) {
                    activeIds.add(mapping.id());
                    startMySql(mapping, source, now);
                    continue;
                }
                if (now < nextPollAt.getOrDefault(mapping.id(), 0L)) continue;
                nextPollAt.put(mapping.id(), now + source.pollIntervalMs());
                if (source.dbType() == Types.DatabaseType.SQLSERVER) pollSqlServer(mapping, source);
                else {
                    long groupId = captureGroups.ensureForMapping(mapping.id());
                    if (polledGroups.add(groupId)) pollSnapshot(mapping, source);
                }
            } catch (Exception e) {
                statuses.put(mapping.id(), new PollStatus(false, null, rootMessage(e)));
                recordRuntimeFailure(mapping.id(), e);
                log.warn("CDC poll failed for mapping {}: {}", mapping.id(), rootMessage(e));
            }
        }
        new ArrayList<>(mysqlWorkers.keySet()).stream().filter(id -> !activeIds.contains(id)).forEach(this::stopMySql);
    }

    private void startMySql(TableMapping mapping, com.cdcplatform.model.SourceConfig source, long now) throws Exception {
        String database = effectiveSourceDatabase(mapping, source);
        String signature = source.host() + ":" + source.port() + "/" + database + ":" +
                source.username() + ":" + mapping.sourceSchema() + ":" + mapping.sourceTable();
        var current = mysqlWorkers.get(mapping.id());
        if (current != null && current.signature().equals(signature)) return;
        if (current != null) stopMySql(mapping.id());
        if (now < nextPollAt.getOrDefault(mapping.id(), 0L)) return;
        nextPollAt.put(mapping.id(), now + Math.max(source.pollIntervalMs(), 1000));

        String checkpoint = repository.checkpoint(mapping.id());
        var taskTable = tasks.tableForMapping(mapping.id());
        String pendingSnapshot = taskTable == null ? null : tasks.snapshotStartPosition(taskTable.id());
        if (pendingSnapshot != null) {
            repository.saveCheckpoint(mapping.id(), pendingSnapshot);
            checkpoint = pendingSnapshot;
        }
        BinlogPosition position = checkpoint != null && checkpoint.startsWith("BINLOG:")
                ? parseBinlogPosition(checkpoint) : currentBinlogPosition(source);
        if (checkpoint == null || !checkpoint.startsWith("BINLOG:") || pendingSnapshot != null) {
            repository.clearSnapshot(mapping.id());
            repository.saveCheckpoint(mapping.id(), position.marker());
            if (tasks.syncModeForMapping(mapping.id()) == Types.SyncMode.FULL_INCREMENTAL) {
                if (taskTable != null) tasks.beginSnapshot(taskTable.id(), position.marker());
                try (var connection = DriverManager.getConnection(connections.streamingUrl(source, database), source.username(), source.password())) {
                    deliverInitialSnapshot(mapping, connection, database);
                }
                if (taskTable != null) tasks.completeSnapshot(taskTable.id(), position.marker());
            }
        }
        var columns = sourceColumns(source, database, mapping.sourceTable());
        var client = new BinaryLogClient(source.host(), source.port(), database, source.username(), source.password());
        client.setServerId(1_000_000L + mapping.id());
        client.setBinlogFilename(position.filename());
        client.setBinlogPosition(position.position());
        client.setKeepAlive(true);
        client.setKeepAliveInterval(5_000);
        var worker = new MySqlWorker(client, signature, new ConcurrentHashMap<>(), columns, position.filename());
        registerMySqlListeners(mapping, source, worker);
        mysqlWorkers.put(mapping.id(), worker);
        Thread.startVirtualThread(() -> {
            try {
                client.connect();
            } catch (Exception e) {
                statuses.put(mapping.id(), new PollStatus(false, repository.checkpoint(mapping.id()), rootMessage(e)));
                recordRuntimeFailure(mapping.id(), e);
                log.warn("MySQL binlog consumer failed for mapping {}: {}", mapping.id(), rootMessage(e));
            } finally {
                mysqlWorkers.remove(mapping.id(), worker);
            }
        });
    }

    private void registerMySqlListeners(TableMapping mapping, com.cdcplatform.model.SourceConfig source, MySqlWorker worker) {
        worker.client().registerLifecycleListener(new BinaryLogClient.LifecycleListener() {
            @Override public void onConnect(BinaryLogClient client) {
                statuses.put(mapping.id(), new PollStatus(true, repository.checkpoint(mapping.id()), null));
            }
            @Override public void onCommunicationFailure(BinaryLogClient client, Exception e) {
                statuses.put(mapping.id(), new PollStatus(false, repository.checkpoint(mapping.id()), rootMessage(e)));
            }
            @Override public void onEventDeserializationFailure(BinaryLogClient client, Exception e) {
                statuses.put(mapping.id(), new PollStatus(false, repository.checkpoint(mapping.id()), rootMessage(e)));
            }
            @Override public void onDisconnect(BinaryLogClient client) { }
        });
        worker.client().registerEventListener(event -> handleMySqlEvent(mapping, source, worker, event));
    }

    private void handleMySqlEvent(TableMapping mapping, com.cdcplatform.model.SourceConfig source,
                                  MySqlWorker worker, Event event) {
        try {
            EventData data = event.getData();
            if (data instanceof RotateEventData rotate) {
                worker.filename(rotate.getBinlogFilename());
                saveBinlogPosition(mapping.id(), worker.filename(), rotate.getBinlogPosition());
                return;
            }
            if (data instanceof TableMapEventData tableMap) {
                worker.tables().put(tableMap.getTableId(), new MySqlTable(tableMap.getDatabase(), tableMap.getTable()));
            } else if (data instanceof WriteRowsEventData rows && matches(worker, rows.getTableId(), source, mapping)) {
                for (var row : rows.getRows()) deliver(new ChangeEvent(null, mapping.id(), Types.Operation.CREATE,
                        Map.of(), mysqlRow(row, rows.getIncludedColumns(), worker.columns()), Instant.now()));
            } else if (data instanceof UpdateRowsEventData rows && matches(worker, rows.getTableId(), source, mapping)) {
                for (var row : rows.getRows()) deliver(new ChangeEvent(null, mapping.id(), Types.Operation.UPDATE,
                        mysqlRow(row.getKey(), rows.getIncludedColumnsBeforeUpdate(), worker.columns()),
                        mysqlRow(row.getValue(), rows.getIncludedColumns(), worker.columns()), Instant.now()));
            } else if (data instanceof DeleteRowsEventData rows && matches(worker, rows.getTableId(), source, mapping)) {
                for (var row : rows.getRows()) deliver(new ChangeEvent(null, mapping.id(), Types.Operation.DELETE,
                        mysqlRow(row, rows.getIncludedColumns(), worker.columns()), Map.of(), Instant.now()));
            }
            var header = event.getHeader();
            if (header instanceof EventHeaderV4 v4 && v4.getNextPosition() > 0)
                saveBinlogPosition(mapping.id(), worker.filename(), v4.getNextPosition());
        } catch (Exception e) {
            statuses.put(mapping.id(), new PollStatus(false, repository.checkpoint(mapping.id()), rootMessage(e)));
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
    }

    private boolean matches(MySqlWorker worker, long tableId, com.cdcplatform.model.SourceConfig source, TableMapping mapping) {
        var table = worker.tables().get(tableId);
        String database = effectiveSourceDatabase(mapping, source);
        return table != null && database.equalsIgnoreCase(table.database()) && mapping.sourceTable().equalsIgnoreCase(table.table());
    }

    private Map<String, Object> mysqlRow(Serializable[] values, BitSet included, List<String> columns) {
        var row = new LinkedHashMap<String, Object>();
        int valueIndex = 0;
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            if (included.get(columnIndex)) row.put(columns.get(columnIndex), normalizeMySqlValue(values[valueIndex++]));
        }
        return row;
    }

    private Object normalizeMySqlValue(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof java.util.Date date)
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        return value;
    }

    private List<String> sourceColumns(com.cdcplatform.model.SourceConfig source, String database, String table) throws SQLException {
        if (!IDENTIFIER.matcher(table).matches()) throw new IllegalArgumentException("Invalid source table identifier");
        try (var connection = DriverManager.getConnection(connections.url(source, database), source.username(), source.password());
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT * FROM `" + table + "` LIMIT 0")) {
            var columns = new ArrayList<String>();
            var metadata = rs.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
            return columns;
        }
    }

    private BinlogPosition currentBinlogPosition(com.cdcplatform.model.SourceConfig source) throws SQLException {
        try (var connection = DriverManager.getConnection(connections.url(source), source.username(), source.password());
             var statement = connection.createStatement()) {
            SQLException first;
            try (var rs = statement.executeQuery("SHOW BINARY LOG STATUS")) {
                if (!rs.next()) throw new IllegalStateException("MySQL binary log status is empty");
                return new BinlogPosition(rs.getString("File"), rs.getLong("Position"));
            } catch (SQLException e) {
                first = e;
            }
            try (var rs = statement.executeQuery("SHOW MASTER STATUS")) {
                if (!rs.next()) throw new IllegalStateException("MySQL binary log status is empty");
                return new BinlogPosition(rs.getString("File"), rs.getLong("Position"));
            } catch (SQLException e) {
                e.addSuppressed(first);
                throw e;
            }
        }
    }

    private BinlogPosition parseBinlogPosition(String checkpoint) {
        int separator = checkpoint.lastIndexOf(':');
        if (separator <= "BINLOG:".length()) throw new IllegalArgumentException("Invalid MySQL binlog checkpoint");
        return new BinlogPosition(checkpoint.substring("BINLOG:".length(), separator),
                Long.parseLong(checkpoint.substring(separator + 1)));
    }

    private void saveBinlogPosition(long mappingId, String filename, long position) {
        String marker = new BinlogPosition(filename, position).marker();
        repository.saveCheckpoint(mappingId, marker);
        statuses.put(mappingId, new PollStatus(true, marker, null));
    }

    private void stopMySql(long mappingId) {
        var worker = mysqlWorkers.remove(mappingId);
        if (worker != null) try { worker.client().disconnect(); } catch (Exception ignored) { }
    }

    @PreDestroy
    public void shutdown() { new ArrayList<>(mysqlWorkers.keySet()).forEach(this::stopMySql); }

    void pollSqlServer(TableMapping mapping, com.cdcplatform.model.SourceConfig source) throws Exception {
        try (var connection = DriverManager.getConnection(connections.url(source, effectiveSourceDatabase(mapping, source)), source.username(), source.password())) {
            String capture = captureInstance(connection, mapping);
            String maxLsn = scalar(connection, "SELECT sys.fn_varbintohexstr(sys.fn_cdc_get_max_lsn())");
            String checkpoint = repository.checkpoint(mapping.id());
            var taskTable = tasks.tableForMapping(mapping.id());
            String pendingSnapshot = taskTable == null ? null : tasks.snapshotStartPosition(taskTable.id());
            if (pendingSnapshot != null) {
                repository.saveCheckpoint(mapping.id(), pendingSnapshot);
                checkpoint = pendingSnapshot;
            }
            if (checkpoint == null || pendingSnapshot != null) {
                String from = pendingSnapshot == null ? maxLsn : pendingSnapshot;
                repository.saveCheckpoint(mapping.id(), from);
                if (tasks.syncModeForMapping(mapping.id()) == Types.SyncMode.FULL_INCREMENTAL) {
                    if (taskTable != null) tasks.beginSnapshot(taskTable.id(), from);
                    deliverInitialSnapshot(mapping, connection, effectiveSourceDatabase(mapping, source));
                    if (taskTable != null) tasks.completeSnapshot(taskTable.id(), from);
                }
                statuses.put(mapping.id(), new PollStatus(true, from, null));
                return;
            }
            if (checkpoint.equalsIgnoreCase(maxLsn)) {
                statuses.put(mapping.id(), new PollStatus(true, maxLsn, null));
                return;
            }
            var events = readChanges(connection, capture, checkpoint, maxLsn, mapping.id());
            for (var event : events) {
                var result = dispatch.accept(event);
                if (!result.delivered()) throw new IllegalStateException(result.error());
            }
            repository.saveCheckpoint(mapping.id(), maxLsn);
            statuses.put(mapping.id(), new PollStatus(true, maxLsn, null));
        }
    }

    void pollSnapshot(TableMapping mapping, com.cdcplatform.model.SourceConfig source) throws Exception {
        String database = effectiveSourceDatabase(mapping, source);
        try (var connection = DriverManager.getConnection(connections.streamingUrl(source, database), source.username(), source.password())) {
            String checkpoint = repository.checkpoint(mapping.id());
            var cycle = deliveryBatches.activeCycle(mapping.id());
            long generation;
            String marker;
            if (cycle == null || "CAPTURING".equals(cycle.status())) {
                generation = System.currentTimeMillis();
                deliveryBatches.beginExtractionCycle(mapping.id(), generation);
                repository.beginSnapshotStage(mapping.id(), generation);
                int rowCount = captureSnapshot(connection, database, mapping.sourceSchema(), mapping.sourceTable(), mapping.id(), generation);
                marker = snapshotMarker(rowCount);
                deliveryBatches.markCyclePrepared(mapping.id(), generation, marker);
            } else {
                generation = cycle.generation();
                marker = cycle.checkpoint();
                if ("READY".equals(cycle.status())) {
                    delivery.finalizeExtractionCycle(mapping.id(), generation);
                    if (deliveryBatches.activeCycle(mapping.id()) == null) {
                        statuses.put(mapping.id(), new PollStatus(true, marker, null));
                        return;
                    }
                }
            }
            var sequence = new java.util.concurrent.atomic.AtomicInteger();
            if (checkpoint == null) {
                if (tasks.syncModeForMapping(mapping.id()) == Types.SyncMode.FULL_INCREMENTAL) {
                    streamStage(mapping.id(), generation, "SELECT row_json FROM cdc_snapshot_stage WHERE mapping_id=:id AND generation=:generation ORDER BY row_key", Types.Operation.READ, Instant.now(), sequence);
                }
                deliveryBatches.markCycleReady(mapping.id(), generation, marker);
                delivery.finalizeExtractionCycle(mapping.id(), generation);
                statuses.put(mapping.id(), new PollStatus(true, marker, null));
                return;
            }
            var capturedAt = Instant.now();
            streamStage(mapping.id(), generation, "SELECT s.row_json FROM cdc_snapshot s LEFT JOIN cdc_snapshot_stage n ON n.mapping_id=s.mapping_id AND n.generation=:generation AND n.row_key=s.row_key WHERE s.mapping_id=:id AND n.row_key IS NULL ORDER BY s.row_key", Types.Operation.DELETE, capturedAt, sequence);
            streamStage(mapping.id(), generation, "SELECT n.row_json FROM cdc_snapshot_stage n LEFT JOIN cdc_snapshot s ON s.mapping_id=n.mapping_id AND s.row_key=n.row_key WHERE n.mapping_id=:id AND n.generation=:generation AND s.row_key IS NULL ORDER BY n.row_key", Types.Operation.CREATE, capturedAt, sequence);
            streamUpdates(mapping.id(), generation, capturedAt, sequence);
            deliveryBatches.markCycleReady(mapping.id(), generation, marker);
            delivery.finalizeExtractionCycle(mapping.id(), generation);
            statuses.put(mapping.id(), new PollStatus(true, marker, null));
        }
    }

    public Map<Long, PollStatus> statuses() { return Map.copyOf(statuses); }
    public void reset(long mappingId) {
        stopMySql(mappingId);
        repository.clearCheckpoint(mappingId);
        repository.clearSnapshot(mappingId);
        nextPollAt.remove(mappingId);
        statuses.remove(mappingId);
    }

    private int captureSnapshot(Connection connection, String catalog, String schema, String table, long mappingId, long generation) throws Exception {
        if (!IDENTIFIER.matcher(table).matches()) throw new IllegalArgumentException("Invalid source table identifier");
        boolean sqlServer = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft");
        String effectiveSchema = sqlServer ? (schema == null || schema.isBlank() ? "dbo" : schema) : null;
        if (effectiveSchema != null && !IDENTIFIER.matcher(effectiveSchema).matches()) throw new IllegalArgumentException("Invalid source schema identifier");
        var keyColumns = primaryKeys(connection, catalog, effectiveSchema, table);
        var batch = new LinkedHashMap<String, String>();
        int count = 0;
        String qualified = sqlServer ? "[" + effectiveSchema + "].[" + table + "]" : "`" + table + "`";
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(1000);
            statement.setQueryTimeout(300);
            try (var rs = statement.executeQuery("SELECT * FROM " + qualified)) {
            var metadata = rs.getMetaData();
            var columns = new ArrayList<String>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
            if (keyColumns.isEmpty()) {
                columns.stream().filter(column -> "id".equalsIgnoreCase(column)).findFirst().ifPresent(keyColumns::add);
            }
            if (keyColumns.isEmpty()) throw new IllegalStateException("Snapshot polling requires a primary key or id column");
            while (rs.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) row.put(columns.get(i - 1), rs.getObject(i));
                var key = new ArrayList<Object>();
                for (String column : keyColumns) key.add(valueIgnoreCase(row, column));
                String serializedKey = json.writeValueAsString(key);
                if (batch.put(serializedKey, json.writeValueAsString(row)) != null)
                    throw new IllegalStateException("Duplicate snapshot key: " + serializedKey);
                count++;
                if (batch.size() >= 1000) { repository.appendSnapshotStage(mappingId, generation, batch); batch.clear(); }
            }
            }
        }
        if (!batch.isEmpty()) repository.appendSnapshotStage(mappingId, generation, batch);
        return count;
    }

    private List<String> primaryKeys(Connection connection, String catalog, String schema, String table) throws SQLException {
        var ordered = new TreeMap<Short, String>();
        try (var keys = connection.getMetaData().getPrimaryKeys(catalog, schema, table)) {
            while (keys.next()) ordered.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }

    private Object valueIgnoreCase(Map<String, Object> row, String name) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).findFirst()
                .map(Map.Entry::getValue).orElseThrow(() -> new IllegalStateException("Snapshot key column not found: " + name));
    }

    private Map<String, Object> readRow(String value) throws Exception {
        return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private void deliver(ChangeEvent event) {
        var result = dispatch.accept(event);
        if (!result.delivered()) throw new IllegalStateException(result.error());
    }

    private void deliverInitialSnapshot(TableMapping mapping, Connection connection, String catalog) throws Exception {
        String table = mapping.sourceTable();
        if (!IDENTIFIER.matcher(table).matches()) throw new IllegalArgumentException("Invalid source table identifier");
        boolean sqlServer = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft");
        String schema = sqlServer ? (mapping.sourceSchema() == null || mapping.sourceSchema().isBlank() ? "dbo" : mapping.sourceSchema()) : null;
        if (schema != null && !IDENTIFIER.matcher(schema).matches()) throw new IllegalArgumentException("Invalid source schema identifier");
        String qualified = sqlServer ? "[" + schema + "].[" + table + "]" : "`" + table + "`";
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(1000);
            statement.setQueryTimeout(300);
            try (var rs = statement.executeQuery("SELECT * FROM " + qualified)) {
                var md = rs.getMetaData();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    deliver(new ChangeEvent(null, mapping.id(), Types.Operation.READ, Map.of(), row, Instant.now()));
                }
            }
        }
    }

    private void streamStage(long mappingId, long generation, String sql, Types.Operation operation) throws Exception {
        streamStage(mappingId, generation, sql, operation, Instant.now(), new java.util.concurrent.atomic.AtomicInteger());
    }

    private void streamStage(long mappingId, long generation, String sql, Types.Operation operation, Instant capturedAt,
                             java.util.concurrent.atomic.AtomicInteger sequence) throws Exception {
        var buffer = new ArrayList<ChangeEvent>(deliveryBatchSize(mappingId));
        try {
            repository.visitSnapshotStage(mappingId, generation, sql, rs -> {
                try {
                    String value = rs.getString(1);
                    buffer.add(new ChangeEvent(null, mappingId, operation,
                            operation == Types.Operation.DELETE ? readRow(value) : Map.of(),
                            operation == Types.Operation.DELETE ? Map.of() : readRow(value), capturedAt));
                    if (buffer.size() >= deliveryBatchSize(mappingId)) flushBatch(mappingId, generation, operation, buffer, sequence);
                } catch (Exception e) { throw new SQLException("Snapshot event delivery failed", e); }
            });
            flushBatch(mappingId, generation, operation, buffer, sequence);
        } catch (org.springframework.jdbc.UncategorizedSQLException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private void streamUpdates(long mappingId, long generation, Instant capturedAt,
                               java.util.concurrent.atomic.AtomicInteger sequence) throws Exception {
        String sql = "SELECT s.row_json,n.row_json FROM cdc_snapshot s JOIN cdc_snapshot_stage n " +
                "ON n.mapping_id=s.mapping_id AND n.generation=:generation AND n.row_key=s.row_key " +
                "WHERE s.mapping_id=:id AND s.row_json<>n.row_json ORDER BY s.row_key";
        var buffer = new ArrayList<ChangeEvent>(deliveryBatchSize(mappingId));
        repository.visitSnapshotStage(mappingId, generation, sql, rs -> {
            try {
                buffer.add(new ChangeEvent(null, mappingId, Types.Operation.UPDATE,
                        readRow(rs.getString(1)), readRow(rs.getString(2)), capturedAt));
                if (buffer.size() >= deliveryBatchSize(mappingId)) flushBatch(mappingId, generation, Types.Operation.UPDATE, buffer, sequence);
            } catch (Exception e) { throw new SQLException("Snapshot update delivery failed", e); }
        });
        flushBatch(mappingId, generation, Types.Operation.UPDATE, buffer, sequence);
    }

    private void flushBatch(long mappingId, long generation, Types.Operation operation, List<ChangeEvent> buffer,
                            java.util.concurrent.atomic.AtomicInteger sequence) {
        if (buffer.isEmpty()) return;
        int part = sequence.getAndIncrement();
        var events = new ArrayList<>(buffer); buffer.clear();
        String position = "SNAPSHOT:" + generation + ":" + operation + ":" + part;
        var result = dispatch.acceptBatch(events, position, position, generation);
        if (!result.delivered()) throw new IllegalStateException(result.error());
    }

    private int deliveryBatchSize(long mappingId) {
        var table = tasks.tableForMapping(mappingId);
        return table != null && table.incrementalProfile() == Types.IncrementalProfile.FREQUENT ? 2000 : 500;
    }

    private void recordRuntimeFailure(long mappingId, Throwable error) {
        var taskId = tasks.taskIdForMapping(mappingId);
        if (taskId == null) return;
        var table = tasks.tableForMapping(mappingId);
        tasks.recordFailure(mappingId, rootMessage(error));
        repository.setMappingEnabled(mappingId, false);
        tasks.addLog(taskId, table == null ? null : table.id(), "ERROR", "CDC 读取失败：" + rootMessage(error));
        tasks.createAlert(taskId, table == null ? null : table.id(), Types.AlertType.TABLE_FAILURE,
                "CDC 读取失败", rootMessage(error));
    }

    private String snapshotMarker(int rowCount) { return "SNAPSHOT:" + rowCount + ":" + Instant.now(); }

    private String effectiveSourceDatabase(TableMapping mapping, com.cdcplatform.model.SourceConfig source) {
        String database = mapping.sourceDatabase();
        if (database == null || database.isBlank()) database = source.databaseName();
        if (database == null || database.isBlank()) throw new IllegalStateException("Source database is not selected");
        return database;
    }

    private String captureInstance(Connection c, TableMapping mapping) throws SQLException {
        String sql = "SELECT ct.capture_instance FROM cdc.change_tables ct JOIN sys.tables t ON t.object_id=ct.source_object_id " +
                "JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?";
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, mapping.sourceSchema() == null || mapping.sourceSchema().isBlank() ? "dbo" : mapping.sourceSchema());
            ps.setString(2, mapping.sourceTable());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Source table is not enabled for CDC");
                String value = rs.getString(1);
                if (!IDENTIFIER.matcher(value).matches()) throw new IllegalStateException("Invalid CDC capture instance");
                return value;
            }
        }
    }

    private List<ChangeEvent> readChanges(Connection c, String capture, String from, String to, long mappingId) throws SQLException {
        String sql = "SELECT * FROM cdc.fn_cdc_get_all_changes_" + capture + "(sys.fn_cdc_increment_lsn(CONVERT(binary(10),?,1)),CONVERT(binary(10),?,1),'all update old') " +
                "WHERE __$operation <> 3 ORDER BY __$start_lsn,__$seqval";
        var events = new ArrayList<ChangeEvent>();
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, from); ps.setString(2, to);
            try (var rs = ps.executeQuery()) {
                var md = rs.getMetaData();
                while (rs.next()) {
                    int operation = rs.getInt("__$operation");
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String name = md.getColumnLabel(i);
                        if (!name.startsWith("__$")) row.put(name, rs.getObject(i));
                    }
                    var type = switch (operation) { case 1 -> Types.Operation.DELETE; case 2 -> Types.Operation.CREATE; case 4 -> Types.Operation.UPDATE; default -> Types.Operation.READ; };
                    events.add(new ChangeEvent(null, mappingId, type,
                            type == Types.Operation.DELETE ? row : Map.of(), type == Types.Operation.DELETE ? Map.of() : row, Instant.now()));
                }
            }
        }
        return events;
    }

    private String scalar(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var rs = statement.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
    private String rootMessage(Throwable e) { var t=e; while(t.getCause()!=null)t=t.getCause(); return t.getMessage(); }
    private record BinlogPosition(String filename, long position) {
        String marker() { return "BINLOG:" + filename + ":" + position; }
    }
    private record MySqlTable(String database, String table) {}
    private static final class MySqlWorker {
        private final BinaryLogClient client;
        private final String signature;
        private final Map<Long, MySqlTable> tables;
        private final List<String> columns;
        private volatile String filename;
        private MySqlWorker(BinaryLogClient client, String signature, Map<Long, MySqlTable> tables,
                            List<String> columns, String filename) {
            this.client = client; this.signature = signature; this.tables = tables; this.columns = columns; this.filename = filename;
        }
        BinaryLogClient client() { return client; }
        String signature() { return signature; }
        Map<Long, MySqlTable> tables() { return tables; }
        List<String> columns() { return columns; }
        String filename() { return filename; }
        void filename(String value) { filename = value; }
    }
    public record PollStatus(boolean healthy, String lastLsn, String error) {}
}
