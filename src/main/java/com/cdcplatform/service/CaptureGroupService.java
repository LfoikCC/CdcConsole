package com.cdcplatform.service;

import com.cdcplatform.model.CaptureBatch;
import com.cdcplatform.model.CaptureGroup;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetSubscription;
import com.cdcplatform.model.SubscriptionBatch;
import com.cdcplatform.repository.CaptureGroupRepository;
import com.cdcplatform.repository.ConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Coordinates the source-side identity of a stream independently from target delivery. */
@Service
public class CaptureGroupService {
    private final CaptureGroupRepository repository;
    private final ConfigRepository configs;
    private final SegmentCache cache;
    private final ObjectMapper json;
    private final ConnectionProbeService connections;

    public CaptureGroupService(CaptureGroupRepository repository, ConfigRepository configs,
                               SegmentCache cache, ObjectMapper json, ConnectionProbeService connections) {
        this.repository = repository; this.configs = configs; this.cache = cache; this.json = json; this.connections = connections;
    }

    @PostConstruct
    void migrateLegacyMappings() {
        // V7 installations have one mapping per target. Registering them lazily here
        // creates the compatible one-group/one-subscription representation without
        // touching historical delivery rows or checkpoints.
        try {
            configs.mappings().forEach(mapping -> {
                try { ensureForMapping(mapping.id()); }
                catch (Exception ignored) { /* retain legacy mapping; it can be retried on first delivery */ }
            });
        } catch (Exception ignored) { /* config tables may be unavailable during a partial startup */ }
    }

    public List<CaptureGroup> groups() { return repository.groups(); }
    public CaptureGroup group(long id) { return repository.group(id); }
    public List<TargetSubscription> subscriptions(long groupId) { return repository.subscriptions(groupId); }
    public Map<String, Object> subscriptionOptions(long groupId) {
        CaptureGroup group = repository.group(groupId);
        var source = configs.source(group.sourceId(), true);
        var result = new LinkedHashMap<String, Object>();
        result.put("group", group);
        result.put("source", Map.of("id", source.id(), "name", source.name(), "dbType", source.dbType(),
                "databaseName", source.databaseName(), "sourceDatabase", group.sourceDatabase() == null ? "" : group.sourceDatabase(),
                "sourceSchema", group.sourceSchema() == null ? "" : group.sourceSchema(), "sourceTable", group.sourceTable()));
        if (connections != null) {
            var tables = connections.tables(source, group.sourceDatabase() == null ? source.databaseName() : group.sourceDatabase());
            tables.stream().filter(table -> group.sourceTable().equals(String.valueOf(table.get("table")))
                    && java.util.Objects.equals(blankToNull(group.sourceSchema()), blankToNull(table.get("schema") == null ? null : String.valueOf(table.get("schema")))))
                    .findFirst().ifPresent(table -> result.put("sourceTable", table));
        }
        var existingSubscriptions = subscriptions(groupId);
        result.put("targets", configs.targets());
        result.put("subscriptions", existingSubscriptions);
        existingSubscriptions.stream().map(TargetSubscription::mappingId).filter(java.util.Objects::nonNull).findFirst()
                .map(configs::mapping)
                .ifPresent(mapping -> result.put("publicMapping", Map.of(
                        "columns", mapping.columns(),
                        "includeOperation", mapping.includeOperation(),
                        "operationColumn", mapping.operationColumn(),
                        "includeCapturedAt", mapping.includeCapturedAt(),
                        "capturedAtColumn", mapping.capturedAtColumn(),
                        "capturedAtFormat", mapping.capturedAtFormat())));
        return result;
    }
    public List<TargetSubscription> subscriptionsForMapping(long mappingId) {
        // A subscription joins live CDC fan-out only after its target-specific
        // snapshot and retained-batch catch-up have completed.
        return repository.subscriptions(ensureForMapping(mappingId)).stream()
                .filter(subscription -> "ACTIVE".equalsIgnoreCase(subscription.status())).toList();
    }
    public void setMappingSubscriptionStatus(long mappingId, String status, String error) {
        ensureForMapping(mappingId);
        repository.updateSubscriptionStatusForMapping(mappingId, status, error);
    }

    public long ensureForMapping(long mappingId) {
        TableMapping mapping = configs.mapping(mappingId);
        long groupId = repository.createGroup(mapping.sourceId(), mapping.sourceDatabase(), mapping.sourceSchema(),
                mapping.sourceTable(), "FULL_INCREMENTAL", "{}", 10L * 1024 * 1024 * 1024);
        repository.createSubscription(groupId, mappingId, mapping.targetId(), mapping.targetSchema(), mapping.targetTable(), "{}", 10L * 1024 * 1024 * 1024);
        return groupId;
    }

    public long createGroup(Map<String, Object> body) {
        long sourceId = number(body, "sourceId");
        return repository.createGroup(sourceId, text(body, "sourceDatabase"), text(body, "sourceSchema"),
                required(body, "sourceTable"), textOr(body, "syncMode", "FULL_INCREMENTAL"),
                textOr(body, "canonicalSchemaJson", "{}"), numberOr(body, "maxPendingBytes", 10L * 1024 * 1024 * 1024));
    }

    public long createSubscription(long groupId, Map<String, Object> body) {
        CaptureGroup group = repository.group(groupId);
        long targetId = number(body, "targetId");
        Long mappingId = body.get("mappingId") == null ? null : number(body, "mappingId");
        String targetSchema = text(body, "targetSchema");
        String targetTable = required(body, "targetTable");
        Map<String, String> columns = stringMap(body.get("columns"));
        validateTarget(group, targetId, targetSchema, targetTable, columns, mappingId);
        var override = new LinkedHashMap<String, Object>();
        override.put("columns", columns);
        for (String key : List.of("includeOperation", "operationColumn", "includeCapturedAt", "capturedAtColumn", "capturedAtFormat", "typeConversions", "name", "description"))
            if (body.containsKey(key)) override.put(key, body.get(key));
        String overrideJson = text(body, "mappingOverrideJson");
        if (overrideJson == null || overrideJson.isBlank()) overrideJson = writeJson(override);
        String status = mappingId == null ? "WAITING_INITIALIZATION" : "ACTIVE";
        long subscriptionId = repository.createSubscription(groupId, mappingId, targetId, targetSchema, targetTable,
                overrideJson, numberOr(body, "maxPendingBytes", 10L * 1024 * 1024 * 1024), status);
        // If retained capture segments exist, use them as the new target's initial
        // snapshot; no second CDC reader is started for the source table.
        if (mappingId != null) repository.captureBatches(groupId).forEach(batch -> repository.fanOutToSubscription(batch, subscriptionId));
        return subscriptionId;
    }

    private void validateTarget(CaptureGroup group, long targetId, String schema, String table,
                                Map<String, String> columns, Long mappingId) {
        if (connections == null) return;
        var source = configs.source(group.sourceId(), true);
        var sourceMeta = connections.tables(source, group.sourceDatabase() == null ? source.databaseName() : group.sourceDatabase()).stream()
                .filter(item -> group.sourceTable().equals(String.valueOf(item.get("table")))).findFirst().orElseThrow(() -> new IllegalArgumentException("源表元数据不可用"));
        var target = configs.target(targetId, true);
        if (target.targetType() == com.cdcplatform.model.Types.TargetType.KAFKA) return;
        var targetMeta = connections.tables(target).stream().filter(item -> table.equals(String.valueOf(item.get("table")))
                && java.util.Objects.equals(blankToNull(schema), blankToNull(item.get("schema") == null ? null : String.valueOf(item.get("schema"))))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标表不存在或无法读取元数据"));
        Set<String> sourceColumns = stringSet(sourceMeta.get("columns"));
        Set<String> targetColumns = stringSet(targetMeta.get("columns"));
        if (columns.isEmpty()) throw new IllegalArgumentException("至少配置一个目标字段映射");
        if (!sourceColumns.containsAll(columns.keySet())) throw new IllegalArgumentException("目标订阅包含不存在的源字段");
        if (!targetColumns.containsAll(columns.values())) throw new IllegalArgumentException("目标订阅包含不存在的目标字段");
        List<String> sourceKeys = stringList(sourceMeta.get("primaryKeys"));
        Set<String> targetKeys = stringSet(targetMeta.get("primaryKeys"));
        if (sourceKeys.isEmpty()) throw new IllegalArgumentException("源采集组未发现主键，不能创建幂等目标订阅");
        Set<String> mappedKeys = sourceKeys.stream().map(key -> columns.getOrDefault(key, key)).collect(java.util.stream.Collectors.toSet());
        if (!mappedKeys.equals(targetKeys)) throw new IllegalArgumentException("源主键必须完整映射到目标主键");
    }

    public void actionGroup(long id, String action) {
        String status = switch (action.toLowerCase()) {
            case "start", "resume" -> "RUNNING";
            case "pause" -> "PAUSED";
            case "stop" -> "STOPPED";
            default -> throw new IllegalArgumentException("unsupported capture group action: " + action);
        };
        repository.updateStatus(id, status, null);
    }
    public void actionSubscription(long id, String action) {
        String status = switch (action.toLowerCase()) {
            case "resume", "start" -> "ACTIVE";
            case "pause" -> "PAUSED";
            case "cancel" -> "CANCELLED";
            default -> throw new IllegalArgumentException("unsupported subscription action: " + action);
        };
        repository.updateSubscriptionStatus(id, status, null);
    }
    public TargetSubscription subscription(long id) { return repository.subscription(id); }
    public void updateSubscription(long id, Map<String, Object> body) {
        TargetSubscription current = repository.subscription(id);
        CaptureGroup group = repository.group(current.captureGroupId());
        long targetId = body.get("targetId") == null ? current.targetId() : number(body, "targetId");
        String schema = body.get("targetSchema") == null ? current.targetSchema() : text(body, "targetSchema");
        String table = body.get("targetTable") == null ? current.targetTable() : text(body, "targetTable");
        Map<String, String> columns = body.containsKey("columns") ? stringMap(body.get("columns")) : readColumns(current.mappingOverrideJson());
        validateTarget(group, targetId, schema, table, columns, current.mappingId());
        var override = new LinkedHashMap<String, Object>();
        override.put("columns", columns);
        override.putAll(body);
        var copy = new LinkedHashMap<String, Object>(body);
        copy.put("targetId", targetId); copy.put("targetSchema", schema); copy.put("targetTable", table); copy.put("mappingOverrideJson", writeJson(override));
        repository.updateSubscription(id, copy);
    }

    /**
     * Records one source segment and creates one logical delivery item per subscription.
     * Existing V7 delivery remains responsible for actual writes during the compatibility window.
     */
    public void registerCaptureBatch(long mappingId, String sourceStart, String sourceEnd, long generation,
                                     String segmentPath, String checksum, int eventCount, long payloadBytes) {
        long groupId = ensureForMapping(mappingId);
        String batchId = "capture-" + groupId + "-" + generation + "-" + checksum;
        CaptureBatch batch = new CaptureBatch(batchId, groupId, generation, sourceStart, sourceEnd, segmentPath,
                checksum, eventCount, payloadBytes, "STAGED", OffsetDateTime.now());
        if (repository.registerCaptureBatch(batch)) {
            repository.updateCapturePosition(groupId, sourceEnd == null ? batchId : sourceEnd, generation);
            repository.fanOut(batch);
        }
    }

    public List<CaptureBatch> captureBatches(long groupId) { return repository.captureBatches(groupId); }
    public List<SubscriptionBatch> subscriptionBatches(long subscriptionId) { return repository.subscriptionBatches(subscriptionId); }
    public void retrySubscriptionBatch(long subscriptionId, String batchId) {
        // Kept deliberately idempotent: a retry only moves this subscription item back to STAGED.
        repository.subscription(subscriptionId);
        repository.retrySubscriptionBatch(subscriptionId, batchId);
    }
    public void markDelivered(long mappingId, String position) {
        TargetSubscription subscription = repository.subscriptionForMapping(mappingId);
        if (subscription == null) return;
        repository.updateDeliveryPosition(subscription.id(), position);
        repository.updateSubscriptionCounters(subscription.id());
    }
    public void markBatch(long mappingId, String checksum, String status, String error) {
        repository.markSubscriptionBatch(mappingId, checksum, status, error);
    }

    private long number(Map<String, Object> body, String key) { Object value = body.get(key); if (value == null) throw new IllegalArgumentException(key + " is required"); return Long.parseLong(String.valueOf(value)); }
    private long numberOr(Map<String, Object> body, String key, long fallback) { return body.get(key) == null ? fallback : number(body, key); }
    private String required(Map<String, Object> body, String key) { String value = text(body, key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required"); return value; }
    private String text(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? null : String.valueOf(value); }
    private String textOr(Map<String, Object> body, String key, String fallback) { String value = text(body, key); return value == null || value.isBlank() ? fallback : value; }
    private Map<String, String> stringMap(Object value) { if (!(value instanceof Map<?, ?> map)) return Map.of(); var result = new LinkedHashMap<String, String>(); map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item))); return result; }
    private Set<String> stringSet(Object value) { return new java.util.LinkedHashSet<>(stringList(value)); }
    private List<String> stringList(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().map(String::valueOf).toList(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String writeJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("目标订阅映射配置无效", e); } }
    private Map<String, String> readColumns(String value) { try { if (value == null || value.isBlank()) return Map.of(); var node=json.readTree(value).path("columns"); return stringMap(json.convertValue(node, Map.class)); } catch (Exception e) { return Map.of(); } }
}
