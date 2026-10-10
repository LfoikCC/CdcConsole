package com.cdcplatform.service;

import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.SyncTask;
import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.service.delivery.TemporalValueNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
public class TaskRuntimeCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeCoordinator.class);
    private static final Set<TaskStatus> ACTIVE = EnumSet.of(TaskStatus.STARTING, TaskStatus.SNAPSHOTTING, TaskStatus.RUNNING, TaskStatus.DEGRADED);
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final EventDispatchService dispatch;
    private final DeliveryService delivery;
    private final CaptureGroupService captureGroups;
    private final RuntimeEventService events;
    private final ObjectMapper json;
    private final Path directory;
    private final int offsetFlushIntervalMs;
    private final int lightSnapshotSize;
    private final int heavySnapshotSize;
    private final int steadyBatchSize;
    private final int frequentBatchSize;
    private final int steadyPollIntervalMs;
    private final int frequentPollIntervalMs;
    private final Map<Long, Worker> workers = new ConcurrentHashMap<>();
    private final Map<Long, Long> groupWorkers = new ConcurrentHashMap<>();
    private final Map<Long, SqlServerCdcPoller.PollStatus> statuses = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> readCounts = new ConcurrentHashMap<>();
    private final long serverIdSeed = ThreadLocalRandom.current().nextLong(100_000L, 900_000_000L);
    private final AtomicLong serverIdSequence = new AtomicLong();

    public TaskRuntimeCoordinator(TaskRepository tasks, ConfigRepository configs, EventDispatchService dispatch, DeliveryService delivery,
                                  CaptureGroupService captureGroups,
                                  RuntimeEventService events, ObjectMapper json,
                                  @Value("${cdc.debezium.directory:./data/debezium}") Path directory,
                                  @Value("${cdc.debezium.offset-flush-interval-ms:1000}") int offsetFlushIntervalMs,
                                  @Value("${cdc.profiles.light-snapshot-chunk-size:10000}") int lightSnapshotSize,
                                  @Value("${cdc.profiles.heavy-snapshot-chunk-size:50000}") int heavySnapshotSize,
                                  @Value("${cdc.profiles.steady-batch-size:500}") int steadyBatchSize,
                                  @Value("${cdc.profiles.frequent-batch-size:2000}") int frequentBatchSize,
                                  @Value("${cdc.profiles.steady-poll-interval-ms:1000}") int steadyPollIntervalMs,
                                  @Value("${cdc.profiles.frequent-poll-interval-ms:200}") int frequentPollIntervalMs) {
        this.tasks = tasks; this.configs = configs; this.dispatch = dispatch; this.delivery = delivery; this.captureGroups = captureGroups; this.events = events;
        this.json = json; this.directory = directory; this.offsetFlushIntervalMs = offsetFlushIntervalMs;
        this.lightSnapshotSize = lightSnapshotSize; this.heavySnapshotSize = heavySnapshotSize;
        this.steadyBatchSize = steadyBatchSize; this.frequentBatchSize = frequentBatchSize;
        this.steadyPollIntervalMs = steadyPollIntervalMs; this.frequentPollIntervalMs = frequentPollIntervalMs;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        var active = new HashSet<Long>();
        for (var task : tasks.tasks()) {
            if (!ACTIVE.contains(task.status())) continue;
            SourceConfig source = configs.source(task.sourceId(), true);
            if (source.dbType() == DatabaseType.STARROCKS) continue;
            // Runtime rows include hidden target-subscription mappings. Source
            // capture is still deduplicated by capture group below.
            for (var table : tasks.runtimeTables(task.id())) {
                if (!configs.mapping(table.mappingId()).enabled()) continue;
                active.add(table.mappingId());
                if (delivery.hasPending(table.mappingId())) { delivery.trigger(table.mappingId()); continue; }
                long groupId = captureGroups.ensureForMapping(table.mappingId());
                Long groupWorker = groupWorkers.get(groupId);
                if (groupWorker != null && workers.containsKey(groupWorker)) continue;
                if (!workers.containsKey(table.mappingId())) {
                    try { startWorker(task, table, source, groupId); }
                    catch (Exception e) { fail(task, table, e); }
                }
            }
        }
        for (Long id : new ArrayList<>(workers.keySet())) if (!active.contains(id)) close(id);
    }

    public Map<Long, SqlServerCdcPoller.PollStatus> statuses() { return Map.copyOf(statuses); }
    public long readCount(long mappingId) { return readCounts.getOrDefault(mappingId, new AtomicLong()).get(); }

    public void stop(long mappingId, boolean reset) {
        close(mappingId);
        statuses.remove(mappingId);
        if (reset) {
            long groupId = captureGroups.ensureForMapping(mappingId);
            try { Files.deleteIfExists(offsetPath(groupId)); Files.deleteIfExists(historyPath(groupId)); }
            catch (Exception e) { throw new IllegalStateException("无法重置 Debezium 位点：" + e.getMessage(), e); }
        }
    }

    private void startWorker(SyncTask task, SyncTaskTable table, SourceConfig source, long groupId) throws Exception {
        Files.createDirectories(directory);
        Properties properties = properties(task, table, source, nextServerId(groupId), groupId);
        var engine = DebeziumEngine.create(Json.class)
                .using(properties)
                .notifying((List<io.debezium.engine.ChangeEvent<String, String>> records,
                            DebeziumEngine.RecordCommitter<io.debezium.engine.ChangeEvent<String, String>> committer) -> {
                    try {
                        var captured = new ArrayList<CapturedRecord>();
                        for (var record : records) {
                            var value = captureRecord(task, table, record);
                            if (value != null) captured.add(value);
                        }
                        if (!captured.isEmpty()) {
                            delivery.stage(captured.stream().map(CapturedRecord::event).toList(),
                                    captured.getFirst().position(), captured.getLast().position(), 0);
                        }
                        for (var record : records) committer.markProcessed(record);
                        committer.markBatchFinished();
                    } catch (Exception e) {
                        fail(task, table, e);
                        throw new IllegalStateException("CDC 批次落盘失败，采集位点未提交", e);
                    }
                })
                .using((success, message, error) -> {
                    workers.remove(table.mappingId());
                    groupWorkers.remove(groupId, table.mappingId());
                    if (!success && configs.mapping(table.mappingId()).enabled())
                        fail(task, table, error == null ? new IllegalStateException(message) : error);
                })
                .build();
        var worker = new Worker(engine);
        if (groupWorkers.putIfAbsent(groupId, table.mappingId()) != null) { engine.close(); return; }
        if (workers.putIfAbsent(table.mappingId(), worker) != null) { groupWorkers.remove(groupId, table.mappingId()); engine.close(); return; }
        Thread.startVirtualThread(() -> {
            try { engine.run(); }
            catch (Exception e) { if (configs.mapping(table.mappingId()).enabled()) fail(task, table, e); }
            finally { workers.remove(table.mappingId(), worker); groupWorkers.remove(groupId, table.mappingId()); }
        });
    }

    Properties properties(SyncTask task, SyncTaskTable table, SourceConfig source) {
        return properties(task, table, source, nextServerId(table.mappingId()));
    }

    private Properties properties(SyncTask task, SyncTaskTable table, SourceConfig source, long serverId) {
        return properties(task, table, source, serverId, table.mappingId());
    }

    private Properties properties(SyncTask task, SyncTaskTable table, SourceConfig source, long serverId, long captureGroupId) {
        var p = new Properties();
        p.setProperty("name", "cdc-capture-group-" + captureGroupId);
        p.setProperty("connector.class", source.dbType() == DatabaseType.MYSQL
                ? "io.debezium.connector.mysql.MySqlConnector" : "io.debezium.connector.sqlserver.SqlServerConnector");
        p.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        p.setProperty("offset.storage.file.filename", offsetPath(captureGroupId).toAbsolutePath().toString());
        p.setProperty("offset.flush.interval.ms", String.valueOf(offsetFlushIntervalMs));
        p.setProperty("topic.prefix", "cdc_capture_group_" + captureGroupId);
        p.setProperty("database.hostname", source.host());
        p.setProperty("database.port", String.valueOf(source.port()));
        p.setProperty("database.user", source.username());
        p.setProperty("database.password", source.password());
        p.setProperty("snapshot.mode", task.syncMode() == SyncMode.FULL_INCREMENTAL ? "initial" : "no_data");
        p.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory");
        p.setProperty("schema.history.internal.file.filename", historyPath(captureGroupId).toAbsolutePath().toString());
        p.setProperty("include.schema.changes", "true");
        p.setProperty("heartbeat.interval.ms", "1000");
        // Keep values usable by the JSON event path. The default BASE64 mode
        // turns MySQL DECIMAL values into opaque byte strings such as "TiA=".
        p.setProperty("decimal.handling.mode", "string");
        int batch = table.incrementalProfile() == IncrementalProfile.FREQUENT ? frequentBatchSize : steadyBatchSize;
        p.setProperty("snapshot.fetch.size", String.valueOf(table.snapshotProfile() == SnapshotProfile.HEAVY
                ? heavySnapshotSize : lightSnapshotSize));
        p.setProperty("max.batch.size", String.valueOf(batch));
        p.setProperty("max.queue.size", String.valueOf(Math.max(2 * batch, batch + 1)));
        p.setProperty("poll.interval.ms", String.valueOf(table.incrementalProfile() == IncrementalProfile.FREQUENT
                ? frequentPollIntervalMs : steadyPollIntervalMs));
        if (source.dbType() == DatabaseType.MYSQL) {
            p.setProperty("database.server.id", String.valueOf(serverId));
            p.setProperty("database.include.list", Pattern.quote(table.sourceDatabase()));
            p.setProperty("table.include.list", Pattern.quote(table.sourceDatabase() + "." + table.sourceTable()));
        } else {
            p.setProperty("database.names", table.sourceDatabase());
            p.setProperty("database.encrypt", "false");
            p.setProperty("database.trustServerCertificate", "true");
            String schema = table.sourceSchema() == null || table.sourceSchema().isBlank() ? "dbo" : table.sourceSchema();
            p.setProperty("table.include.list", Pattern.quote(table.sourceDatabase() + "." + schema + "." + table.sourceTable()));
        }
        return p;
    }

    private CapturedRecord captureRecord(SyncTask task, SyncTaskTable table,
                                         io.debezium.engine.ChangeEvent<String, String> record) throws Exception {
        if (record.value() == null) return null;
        JsonNode root = json.readTree(record.value());
        JsonNode payload = root.has("payload") ? root.path("payload") : root;
        String op = payload.path("op").asText("");
        if (op.isBlank()) {
            if (payload.has("ts_ms") && !payload.has("source")) markStreaming(table, "heartbeat");
            return null;
        }
        Operation operation = switch (op) {
            case "c" -> Operation.CREATE; case "u" -> Operation.UPDATE;
            case "d" -> Operation.DELETE; case "r" -> Operation.READ;
            default -> null;
        };
        if (operation == null) return null;
        readCounts.computeIfAbsent(table.mappingId(), ignored -> new AtomicLong()).incrementAndGet();
        Map<String, Object> before = row(payload.path("before"));
        Map<String, Object> after = row(payload.path("after"));
        String position = sourcePosition(payload);
        var eventId = UUID.nameUUIDFromBytes((table.mappingId() + "|" + position + "|" + operation.name())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var event = new ChangeEvent(eventId, table.mappingId(), operation, before, after, Instant.now());
        long sourceTimestamp = payload.path("source").path("ts_ms").asLong(0);
        if (sourceTimestamp > 0) tasks.updateSourceLag(table.mappingId(), System.currentTimeMillis() - sourceTimestamp);
        if (operation == Operation.READ && !"last".equalsIgnoreCase(payload.path("source").path("snapshot").asText()))
            tasks.updateTableStatus(table.id(), TableStatus.SNAPSHOTTING, null);
        else markStreaming(table, position);
        return new CapturedRecord(event, position);
    }

    private String sourcePosition(JsonNode payload) {
        JsonNode source = payload.path("source");
        String file = source.path("file").asText("");
        if (!file.isBlank()) return "MYSQL:" + file + ":" + source.path("pos").asLong(0) + ":" + source.path("row").asLong(0);
        String commit = source.path("commit_lsn").asText("");
        if (!commit.isBlank()) return "SQLSERVER:" + commit + ":" + source.path("change_lsn").asText("") +
                ":" + source.path("event_serial_no").asLong(0);
        return "EVENT:" + payload.path("ts_ms").asLong(System.currentTimeMillis()) + ":" + UUID.randomUUID();
    }

    private void markStreaming(SyncTaskTable table, String position) {
        statuses.put(table.mappingId(), new SqlServerCdcPoller.PollStatus(true, position, null));
    }

    private Map<String, Object> row(JsonNode node) {
        if (!node.isObject()) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        node.fields().forEachRemaining(entry -> {
            Object value = json.convertValue(entry.getValue(), Object.class);
            result.put(entry.getKey(), TemporalValueNormalizer.normalize(entry.getKey(), value));
        });
        return result;
    }

    private long nextServerId(long mappingId) {
        long sequence = serverIdSequence.getAndIncrement();
        long candidate = Math.floorMod(serverIdSeed + mappingId * 131L + sequence * 7919L, 2_000_000_000L);
        return Math.max(1L, candidate);
    }

    private void fail(SyncTask task, SyncTaskTable table, Throwable error) {
        String message = rootMessage(error);
        if (!configs.mapping(table.mappingId()).enabled()) return;
        configs.setMappingEnabled(table.mappingId(), false);
        tasks.recordFailure(table.mappingId(), message);
        tasks.addLog(task.id(), table.id(), "ERROR", "Debezium 同步失败：" + message);
        tasks.createAlert(task.id(), table.id(), message.toLowerCase(Locale.ROOT).contains("offset")
                        ? AlertType.CHECKPOINT_EXPIRED : AlertType.TABLE_FAILURE,
                "同步表运行失败", message);
        statuses.put(table.mappingId(), new SqlServerCdcPoller.PollStatus(false, null, message));
        events.publish("task-status", Map.of("taskId", task.id(), "status", "DEGRADED"));
        log.warn("Debezium task {} table {} failed: {}", task.id(), table.id(), message);
    }

    private void close(long mappingId) {
        var worker = workers.remove(mappingId);
        groupWorkers.entrySet().removeIf(entry -> entry.getValue().equals(mappingId));
        if (worker != null) try { worker.engine().close(); } catch (Exception e) { log.warn("Failed to close Debezium worker {}", mappingId, e); }
    }
    private Path offsetPath(long mappingId) { return directory.resolve("offset-" + mappingId + ".dat"); }
    private Path historyPath(long mappingId) { return directory.resolve("history-" + mappingId + ".dat"); }
    private String rootMessage(Throwable error) { var t=error; while(t.getCause()!=null)t=t.getCause(); return Objects.toString(t.getMessage(), t.getClass().getSimpleName()); }
    @PreDestroy void shutdown() { new ArrayList<>(workers.keySet()).forEach(this::close); }
    private record Worker(DebeziumEngine<io.debezium.engine.ChangeEvent<String, String>> engine) {}
    private record CapturedRecord(ChangeEvent event, String position) {}
}
