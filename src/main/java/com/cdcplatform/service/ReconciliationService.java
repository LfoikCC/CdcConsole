package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.ReconciliationRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.TaskSubscriptionRepository;
import com.cdcplatform.service.reconciliation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class ReconciliationService {
    private static final int DETAIL_LIMIT = 1000;
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final ReconciliationRepository repository;
    private final ConnectionProbeService connections;
    private final ReconciliationAdapterRegistry adapters;
    private final RuntimeEventService events;
    private final ObjectMapper json;
    private final TaskSubscriptionRepository targetSubscriptions;
    private final Semaphore globalSlots = new Semaphore(2);
    private final Set<Long> activeTasks = ConcurrentHashMap.newKeySet();

    public ReconciliationService(TaskRepository tasks, ConfigRepository configs, ReconciliationRepository repository,
                                 ConnectionProbeService connections, ReconciliationAdapterRegistry adapters,
                                 RuntimeEventService events, ObjectMapper json, TaskSubscriptionRepository targetSubscriptions) {
        this.tasks = tasks; this.configs = configs; this.repository = repository; this.connections = connections;
        this.adapters = adapters; this.events = events; this.json = json; this.targetSubscriptions=targetSubscriptions;
    }

    public long submit(long taskId, ReconciliationRequest request, ReconciliationTrigger trigger) {
        var task = tasks.task(taskId);
        var selected = configuredTables(task, request.tableIds());
        if (selected.isEmpty()) throw new IllegalArgumentException("任务没有已配置数据核对的表");
        if (configs.target(task.targetId(), true).targetType() == TargetType.KAFKA)
            throw new IllegalArgumentException("Kafka 目标不支持数据核对");
        return submit(task, null, task.targetId(), selected, request, trigger);
    }

    public long submitForSubscription(long taskId, long subscriptionId, ReconciliationRequest request, ReconciliationTrigger trigger) {
        var task=tasks.task(taskId);
        var subscription=targetSubscriptions.subscription(subscriptionId);
        if(subscription.taskId()!=taskId) throw new IllegalArgumentException("目标订阅不属于当前任务");
        if(configs.target(subscription.targetId(),true).targetType()==TargetType.KAFKA) throw new IllegalArgumentException("Kafka 目标不支持数据核对");
        var selected=subscription.tables().stream().map(TaskTargetSubscriptionTable::mapping)
                .filter(table -> table.reconciliation()!=null && table.reconciliation().enabled())
                .filter(table -> request.tableIds()==null || request.tableIds().isEmpty() || request.tableIds().contains(table.id())).toList();
        if(selected.isEmpty()) throw new IllegalArgumentException("该目标订阅没有已配置数据核对的表");
        return submit(task,subscriptionId,subscription.targetId(),selected,request,trigger);
    }

    private long submit(SyncTask task, Long subscriptionId, long targetId, List<SyncTaskTable> selected,
                        ReconciliationRequest request, ReconciliationTrigger trigger) {
        long taskId=task.id();
        Window window = window(task, request);
        if (isWindowIdempotent(request.mode())) {
            var existing = subscriptionId==null ? repository.existing(taskId, request.mode(), window.start(), window.end())
                    : repository.existing(taskId, subscriptionId, request.mode(), window.start(), window.end());
            if (existing.isPresent()) return existing.getAsLong();
        }
        if (repository.hasActive(taskId) || !activeTasks.add(taskId)) throw new IllegalStateException("该任务已有核对正在运行");
        long runId;
        try {
            runId = repository.begin(taskId, subscriptionId, request.mode(), trigger, window.start(), window.end(),
                    selected.stream().map(SyncTaskTable::id).toList());
        } catch (RuntimeException e) {
            activeTasks.remove(taskId);
            throw e;
        }
        Thread.startVirtualThread(() -> execute(runId, taskId, targetId, selected, request, trigger, window));
        return runId;
    }

    static boolean isWindowIdempotent(ReconciliationMode mode) {
        return mode != ReconciliationMode.FULL;
    }

    private void execute(long runId, long taskId, long targetId, List<SyncTaskTable> selected, ReconciliationRequest request,
                         ReconciliationTrigger trigger, Window window) {
        boolean acquired = false;
        try {
            globalSlots.acquire(); acquired = true; repository.markRunning(runId);
            var task = tasks.task(taskId);
            var source = configs.source(task.sourceId(), true);
            var target = configs.target(targetId, true);
            var sourceAdapter = adapters.source(source.dbType());
            var targetAdapter = adapters.target(target.targetType());
            long totalSource = 0, totalTarget = 0, totalMismatch = 0;
            boolean anyMismatch = false;
            int failedTables = 0;
            var samples = new ArrayList<String>();
            var detailBudget = new DetailBudget();
            for (var table : selected) {
                try (var sourceConnection = DriverManager.getConnection(connections.url(source, table.sourceDatabase()),
                        source.username(), source.password());
                     var targetConnection = DriverManager.getConnection(connections.targetUrl(target),
                             required(target.config(), "username"), required(target.config(), "password"))) {
                    var outcome = reconcileTable(runId, task, table, request, window, sourceConnection, targetConnection,
                            sourceAdapter, targetAdapter, samples, detailBudget);
                    totalSource += outcome.sourceRows(); totalTarget += outcome.targetRows(); totalMismatch += outcome.mismatches();
                    anyMismatch |= !outcome.matched();
                } catch (Exception tableError) {
                    failedTables++;
                    repository.addDetail(runId, table.id(), "FAILED", 0, 0, 0, 0, 0, 0, rootMessage(tableError));
                    tasks.addLog(taskId, table.id(), "ERROR", "数据核对失败：" + rootMessage(tableError));
                }
            }
            String status = failedTables > 0 ? "PARTIAL_FAILED" : anyMismatch ? "MISMATCHED" : "MATCHED";
            String message = failedTables > 0 ? failedTables + " 张表核对失败"
                    : !anyMismatch ? "数据一致" : totalMismatch > 0 ? "发现 " + totalMismatch + " 个明细差异" : "字段聚合不一致";
            repository.finish(runId, status, totalSource, totalTarget, totalMismatch, samples, message);
            if (anyMismatch) tasks.createAlert(taskId, null, AlertType.RECONCILIATION_MISMATCH, "数据核对不一致", message);
            tasks.addLog(taskId, null, !anyMismatch && failedTables == 0 ? "INFO" : "WARN", "数据核对完成：" + message);
            events.publish("reconciliation", Map.of("taskId", taskId, "runId", runId, "status", status));
        } catch (Exception e) {
            repository.finish(runId, "FAILED", 0, 0, 0, List.of(), rootMessage(e));
            tasks.addLog(taskId, null, "ERROR", "数据核对失败：" + rootMessage(e));
        } finally {
            activeTasks.remove(taskId);
            if (acquired) globalSlots.release();
        }
    }

    private TableOutcome reconcileTable(long runId, SyncTask task, SyncTaskTable table,
                                         ReconciliationRequest request, Window window,
                                         Connection source, Connection target,
                                         ReconciliationDatabaseAdapter sourceAdapter,
                                         ReconciliationDatabaseAdapter targetAdapter,
                                         List<String> samples, DetailBudget detailBudget) throws Exception {
        var config = table.reconciliation();
        var sourceSummary = aggregate(source, sourceAdapter, table.sourceSchema(), table.sourceTable(),
                config.sourceTimeColumn(), config.fields(), true, request.mode(), window);
        var targetSummary = aggregate(target, targetAdapter, table.targetSchema(), table.targetTable(),
                config.targetTimeColumn(), config.fields(), false, request.mode(), window);
        boolean rowCountsMatched = sourceSummary.rowCount() == targetSummary.rowCount();
        boolean aggregatesMatched = rowCountsMatched;
        repository.addField(runId, table.id(), "*", "*", "OTHER", ReconciliationMetric.ROW_COUNT.name(),
                Long.toString(sourceSummary.rowCount()), Long.toString(targetSummary.rowCount()), aggregatesMatched);
        for (var field : config.fields()) {
            for (var metric : field.metrics()) {
                String key = metricKey(field.sourceColumn(), metric);
                Object left = sourceSummary.values().get(key), right = targetSummary.values().get(key);
                boolean matched = metricEquals(field.logicalType(), left, right, ZoneId.of(task.timeZone()));
                if (metric == ReconciliationMetric.NULL_RATE) matched &= rowCountsMatched;
                aggregatesMatched &= matched;
                repository.addField(runId, table.id(), field.sourceColumn(), field.targetColumn(),
                        field.logicalType().name(), metric.name(), metricValue(metric, left, sourceSummary.rowCount()),
                        metricValue(metric, right, targetSummary.rowCount()), matched);
            }
        }
        var counts = new DifferenceCounts();
        if (!aggregatesMatched && request.drillDown()) {
            scanAndCompare(runId, task, table, request.mode(), window, source, target, sourceAdapter, targetAdapter,
                    counts, samples, detailBudget, true);
            scanAndCompare(runId, task, table, request.mode(), window, target, source, targetAdapter, sourceAdapter,
                    counts, samples, detailBudget, false);
        }
        verifyDeletes(runId, table, request.mode(), window, target, targetAdapter, counts, samples, detailBudget);
        long mismatches = counts.total();
        String status = mismatches == 0 && aggregatesMatched ? "MATCHED" : "MISMATCHED";
        String message = status.equals("MATCHED") ? "字段聚合一致" : "字段聚合或明细存在差异";
        repository.addDetail(runId, table.id(), status, sourceSummary.rowCount(), targetSummary.rowCount(),
                counts.missing, counts.redundant, counts.fields, counts.deletes, message);
        return new TableOutcome(sourceSummary.rowCount(), targetSummary.rowCount(), mismatches,
                aggregatesMatched && mismatches == 0);
    }

    private AggregateSummary aggregate(Connection connection, ReconciliationDatabaseAdapter adapter,
                                       String schema, String table, String timeColumn,
                                       List<ReconciliationFieldConfig> fields, boolean sourceSide,
                                       ReconciliationMode mode, Window window) throws Exception {
        var expressions = new ArrayList<String>();
        expressions.add(adapter.aggregateExpression(ReconciliationMetric.ROW_COUNT, "*") + " AS m0");
        var keys = new ArrayList<String>();
        int alias = 1;
        for (var field : fields) for (var metric : field.metrics()) {
            String column = adapter.quote(sourceSide ? field.sourceColumn() : field.targetColumn());
            expressions.add(adapter.aggregateExpression(metric, column) + " AS m" + alias++);
            keys.add(metricKey(field.sourceColumn(), metric));
        }
        String sql = "SELECT " + String.join(",", expressions) + " FROM " + adapter.qualified(schema, table)
                + where(adapter, timeColumn, mode);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(120); bindWindow(statement, adapter, mode, window);
            try (var rs = statement.executeQuery()) {
                rs.next(); long rows = rs.getLong(1); var values = new LinkedHashMap<String, Object>();
                for (int i = 0; i < keys.size(); i++) values.put(keys.get(i), rs.getObject(i + 2));
                return new AggregateSummary(rows, values);
            }
        }
    }

    private void scanAndCompare(long runId, SyncTask task, SyncTaskTable table, ReconciliationMode mode, Window window,
                                Connection scanConnection, Connection lookupConnection,
                                ReconciliationDatabaseAdapter scanAdapter, ReconciliationDatabaseAdapter lookupAdapter,
                                DifferenceCounts counts, List<String> samples, DetailBudget detailBudget,
                                boolean sourceToTarget) throws Exception {
        var scanKeys = sourceToTarget ? table.keyColumns() : mappedKeys(table);
        var lookupKeys = sourceToTarget ? mappedKeys(table) : table.keyColumns();
        var scanFields = sourceToTarget ? table.reconciliation().fields().stream().map(ReconciliationFieldConfig::sourceColumn).toList()
                : table.reconciliation().fields().stream().map(ReconciliationFieldConfig::targetColumn).toList();
        var lookupFields = sourceToTarget ? table.reconciliation().fields().stream().map(ReconciliationFieldConfig::targetColumn).toList()
                : table.reconciliation().fields().stream().map(ReconciliationFieldConfig::sourceColumn).toList();
        String scanTime = sourceToTarget ? table.reconciliation().sourceTimeColumn() : table.reconciliation().targetTimeColumn();
        String lookupTime = sourceToTarget ? table.reconciliation().targetTimeColumn() : table.reconciliation().sourceTimeColumn();
        String scanSchema = sourceToTarget ? table.sourceSchema() : table.targetSchema();
        String scanTable = sourceToTarget ? table.sourceTable() : table.targetTable();
        String lookupSchema = sourceToTarget ? table.targetSchema() : table.sourceSchema();
        String lookupTable = sourceToTarget ? table.targetTable() : table.sourceTable();
        int batchSize = Math.min(scanAdapter.batchSize(scanKeys.size()), lookupAdapter.batchSize(lookupKeys.size()));
        var columns = distinct(scanKeys, scanFields);
        String sql = "SELECT " + quoteColumns(scanAdapter, columns) + " FROM " + scanAdapter.qualified(scanSchema, scanTable)
                + where(scanAdapter, scanTime, mode) + " ORDER BY " + quoteColumns(scanAdapter, scanKeys);
        try (var statement = scanConnection.prepareStatement(sql)) {
            scanAdapter.configureStreaming(statement); bindWindow(statement, scanAdapter, mode, window);
            try (var rs = statement.executeQuery()) {
                var batch = new ArrayList<RowData>(batchSize);
                while (rs.next()) {
                    batch.add(readRow(rs, columns, scanKeys));
                    if (batch.size() == batchSize) { compareBatch(runId, task, table, batch, lookupConnection, lookupAdapter,
                            lookupSchema, lookupTable, lookupTime, lookupKeys, lookupFields, mode, window,
                            counts, samples, detailBudget, sourceToTarget); batch.clear(); }
                }
                if (!batch.isEmpty()) compareBatch(runId, task, table, batch, lookupConnection, lookupAdapter,
                        lookupSchema, lookupTable, lookupTime, lookupKeys, lookupFields, mode, window,
                        counts, samples, detailBudget, sourceToTarget);
            }
        }
    }

    private void compareBatch(long runId, SyncTask task, SyncTaskTable table, List<RowData> rows, Connection lookupConnection,
                              ReconciliationDatabaseAdapter adapter, String schema, String tableName, String timeColumn,
                              List<String> keys, List<String> fields, ReconciliationMode mode, Window window,
                              DifferenceCounts counts, List<String> samples, DetailBudget detailBudget,
                              boolean sourceToTarget) throws Exception {
        var found = lookupRows(lookupConnection, adapter, schema, tableName, timeColumn, keys, fields, rows, mode, window);
        for (var row : rows) {
            var other = found.get(keyToken(row.key().values().values()));
            if (other == null) {
                if (sourceToTarget) {
                    counts.missing++; store(runId, table.id(), ReconciliationDiscrepancyType.TARGET_MISSING,
                            row.key(), null, row.values(), null, samples, detailBudget);
                } else {
                    counts.redundant++; store(runId, table.id(), ReconciliationDiscrepancyType.TARGET_REDUNDANT,
                            row.key(), null, null, row.values(), samples, detailBudget);
                }
                continue;
            }
            if (!sourceToTarget) continue;
            for (var field : table.reconciliation().fields()) {
                Object left = row.values().get(field.sourceColumn()), right = other.get(field.targetColumn());
                if (!valueEquals(field.logicalType(), left, right, ZoneId.of(task.timeZone()))) {
                    counts.fields++;
                    store(runId, table.id(), ReconciliationDiscrepancyType.FIELD_MISMATCH, row.key(),
                            field.sourceColumn(), left, right, samples, detailBudget);
                }
            }
        }
    }

    private Map<String, Map<String, Object>> lookupRows(Connection connection, ReconciliationDatabaseAdapter adapter,
                                                        String schema, String table, String timeColumn, List<String> keys,
                                                        List<String> fields, List<RowData> rows,
                                                        ReconciliationMode mode, Window window) throws Exception {
        var columns = distinct(keys, fields);
        String tuples = String.join(" OR ", Collections.nCopies(rows.size(), "(" +
                String.join(" AND ", keys.stream().map(key -> adapter.quote(key) + "=?").toList()) + ")"));
        String sql = "SELECT " + quoteColumns(adapter, columns) + " FROM " + adapter.qualified(schema, table)
                + " WHERE " + adapter.visiblePredicate()
                + (mode == ReconciliationMode.FULL ? "" : " AND " + adapter.quote(timeColumn) + ">=? AND " + adapter.quote(timeColumn) + "<?")
                + " AND (" + tuples + ")";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(120); int index = 1;
            if (mode != ReconciliationMode.FULL) {
                adapter.bindTime(statement, index++, window.start());
                adapter.bindTime(statement, index++, window.end());
            }
            for (var row : rows) for (Object value : row.key().values().values()) statement.setObject(index++, value);
            var result = new HashMap<String, Map<String, Object>>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var values = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < columns.size(); i++) values.put(columns.get(i), rs.getObject(i + 1));
                    result.put(keyToken(keys.stream().map(values::get).toList()), values);
                }
            }
            return result;
        }
    }

    private void verifyDeletes(long runId, SyncTaskTable table, ReconciliationMode mode, Window window,
                               Connection target, ReconciliationDatabaseAdapter adapter,
                               DifferenceCounts counts, List<String> samples, DetailBudget detailBudget) throws Exception {
        var targetKeys = mappedKeys(table);
        int batchSize = adapter.batchSize(targetKeys.size());
        long afterId = 0;
        while (true) {
            var batch = repository.pendingDeletes(table.id(), mode == ReconciliationMode.FULL ? null : window.start(),
                    mode == ReconciliationMode.FULL ? null : window.end(), afterId, batchSize);
            if (batch.isEmpty()) return;
            var rows = batch.stream().map(audit -> {
                var targetKey = new LinkedHashMap<String, Object>();
                for (int i = 0; i < table.keyColumns().size(); i++)
                    targetKey.put(targetKeys.get(i), audit.key().get(table.keyColumns().get(i)));
                return new RowData(new KeyData(targetKey), targetKey);
            }).toList();
            var found = lookupRows(target, adapter, table.targetSchema(), table.targetTable(), null,
                    targetKeys, List.of(), rows, ReconciliationMode.FULL, window);
            for (int i = 0; i < batch.size(); i++) {
                var audit = batch.get(i); var key = audit.key();
                boolean residue = found.containsKey(keyToken(rows.get(i).key().values().values()));
                repository.markDeleteVerified(audit.id(), residue);
                if (residue) { counts.deletes++; store(runId, table.id(), ReconciliationDiscrepancyType.DELETE_RESIDUE,
                        new KeyData(key), null, null, key, samples, detailBudget); }
            }
            afterId = batch.getLast().id();
        }
    }

    private List<SyncTaskTable> configuredTables(SyncTask task, List<Long> requested) {
        var requestedSet = new HashSet<>(requested == null ? List.of() : requested);
        return task.tables().stream().filter(t -> t.reconciliation().enabled())
                .filter(t -> requestedSet.isEmpty() || requestedSet.contains(t.id())).toList();
    }

    private Window window(SyncTask task, ReconciliationRequest request) {
        if (request.mode() == ReconciliationMode.FULL) return new Window(null, null);
        if (request.mode() == ReconciliationMode.RANGE) return new Window(request.startAt(), request.endAt());
        var zone = ZoneId.of(task.timeZone());
        ZonedDateTime end = ZonedDateTime.now(zone).minusMinutes(task.reconciliationDelayMinutes())
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        int minutes = task.reconciliationWindowMinutes();
        long localMinutes = ChronoUnit.MINUTES.between(LocalDateTime.of(1970, 1, 1, 0, 0), end.toLocalDateTime());
        end = end.minusMinutes(Math.floorMod(localMinutes, minutes));
        return new Window(end.minusMinutes(minutes).toOffsetDateTime(), end.toOffsetDateTime());
    }

    private String where(ReconciliationDatabaseAdapter adapter, String timeColumn, ReconciliationMode mode) {
        return " WHERE " + adapter.visiblePredicate() + (mode == ReconciliationMode.FULL ? "" :
                " AND " + adapter.quote(timeColumn) + ">=? AND " + adapter.quote(timeColumn) + "<?");
    }
    private void bindWindow(PreparedStatement statement, ReconciliationDatabaseAdapter adapter,
                            ReconciliationMode mode, Window window) throws SQLException {
        if (mode != ReconciliationMode.FULL) { adapter.bindTime(statement, 1, window.start()); adapter.bindTime(statement, 2, window.end()); }
    }
    private List<String> mappedKeys(SyncTaskTable table) { return table.keyColumns().stream().map(k -> table.columns().getOrDefault(k, k)).toList(); }
    private List<String> distinct(List<String> first, List<String> second) { var out = new ArrayList<>(first); second.forEach(x -> { if (!out.contains(x)) out.add(x); }); return out; }
    private String quoteColumns(ReconciliationDatabaseAdapter adapter, List<String> columns) { return String.join(",", columns.stream().map(adapter::quote).toList()); }
    private RowData readRow(ResultSet rs, List<String> columns, List<String> keys) throws SQLException {
        var values = new LinkedHashMap<String, Object>(); for (int i = 0; i < columns.size(); i++) values.put(columns.get(i), rs.getObject(i + 1));
        var key = new LinkedHashMap<String, Object>(); keys.forEach(k -> key.put(k, values.get(k))); return new RowData(new KeyData(key), values);
    }
    private String metricKey(String field, ReconciliationMetric metric) { return field + "|" + metric.name(); }
    private String keyToken(Collection<?> values) { try { return json.writeValueAsString(values); } catch (Exception e) { throw new IllegalStateException(e); } }
    private boolean metricEquals(ReconciliationLogicalType type, Object left, Object right, ZoneId zone) {
        return valueEquals(type, left, right, zone);
    }
    private boolean valueEquals(ReconciliationLogicalType type, Object left, Object right, ZoneId zone) {
        if (left == null || right == null) return left == right;
        if (type == ReconciliationLogicalType.NUMBER && left instanceof Number && right instanceof Number) {
            var a = new BigDecimal(left.toString()); var b = new BigDecimal(right.toString());
            if (isExactNumber(left) && isExactNumber(right)) return a.compareTo(b) == 0;
            double tolerance = Math.max(1e-6, Math.abs(a.doubleValue()) * 1e-9);
            return a.subtract(b).abs().compareTo(BigDecimal.valueOf(tolerance)) <= 0;
        }
        if (type == ReconciliationLogicalType.DATETIME) return normalizeTime(left, zone).equals(normalizeTime(right, zone));
        if (type == ReconciliationLogicalType.BOOLEAN) return normalizeBoolean(left).equals(normalizeBoolean(right));
        return Objects.equals(String.valueOf(left), String.valueOf(right));
    }
    private boolean isExactNumber(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal;
    }
    private String normalizeTime(Object value, ZoneId zone) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().atZone(zone).toInstant().toString();
        if (value instanceof LocalDateTime time) return time.atZone(zone).toInstant().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay(zone).toInstant().toString();
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        if (value instanceof ZonedDateTime time) return time.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        return String.valueOf(value);
    }
    private String normalizeBoolean(Object value) {
        if (value instanceof Boolean bool) return Boolean.toString(bool);
        if (value instanceof Number number) return Boolean.toString(number.intValue() != 0);
        String text = String.valueOf(value).trim();
        return Boolean.toString(text.equalsIgnoreCase("true") || text.equals("1") || text.equalsIgnoreCase("yes"));
    }
    private String value(Object value) { return value == null ? null : String.valueOf(value); }
    private String metricValue(ReconciliationMetric metric, Object value, long rowCount) {
        return metric == ReconciliationMetric.NULL_RATE ? value(value) + "/" + rowCount : value(value);
    }
    private void store(long runId, long tableId, ReconciliationDiscrepancyType type, KeyData key, String field,
                       Object source, Object target, List<String> samples, DetailBudget detailBudget) {
        if (detailBudget.stored < DETAIL_LIMIT) {
            repository.addDiscrepancy(runId, tableId, type, key.values(), field, source, target);
            detailBudget.stored++;
        }
        if (samples.size() < 100) samples.add(type.name() + ": " + keyToken(key.values().values()));
    }
    private String required(Map<String, Object> values, String key) { Object value = values.get(key); if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("目标连接缺少 " + key); return value.toString(); }
    private String rootMessage(Throwable error) { var t = error; while (t.getCause() != null) t = t.getCause(); return Objects.toString(t.getMessage(), t.getClass().getSimpleName()); }

    private record Window(OffsetDateTime start, OffsetDateTime end) {}
    private record AggregateSummary(long rowCount, Map<String, Object> values) {}
    private record TableOutcome(long sourceRows, long targetRows, long mismatches, boolean matched) {}
    private record KeyData(Map<String, Object> values) {}
    private record RowData(KeyData key, Map<String, Object> values) {}
    private static final class DifferenceCounts { long missing, redundant, fields, deletes; long total() { return missing + redundant + fields + deletes; } }
    private static final class DetailBudget { int stored; }
}
