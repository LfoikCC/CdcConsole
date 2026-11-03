package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class TaskConfigurationService {
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final ConnectionProbeService connections;
    private final SqlServerCdcPoller poller;
    private final TaskRuntimeCoordinator coordinator;
    private final RuntimeEventService events;
    private final ObjectMapper json;
    private final DeliveryService delivery;
    private final TaskSubscriptionService targetSubscriptions;

    public TaskConfigurationService(TaskRepository tasks, ConfigRepository configs, ConnectionProbeService connections,
                                    SqlServerCdcPoller poller, TaskRuntimeCoordinator coordinator,
                                    RuntimeEventService events, ObjectMapper json, DeliveryService delivery,
                                    TaskSubscriptionService targetSubscriptions) {
        this.tasks = tasks; this.configs = configs; this.connections = connections; this.poller = poller;
        this.coordinator = coordinator; this.events = events; this.json = json;
        this.delivery = delivery;
        this.targetSubscriptions = targetSubscriptions;
    }

    public long create(SyncTask task) {
        long id = tasks.create(task);
        events.publish("task", Map.of("taskId", id, "action", "created"));
        return id;
    }

    public void update(long id, SyncTask task) {
        tasks.update(id, task);
        events.publish("task", Map.of("taskId", id, "action", "updated"));
    }

    public ValidationReport validate(SyncTask task) { return prepare(task, true).report; }

    public void start(long id, boolean resume) {
        SyncTask task = tasks.task(id);
        Prepared prepared = prepare(task, true);
        List<String> blockingErrors = prepared.report.errors();
        if (resume) {
            var failedLabels = task.tables().stream().filter(table -> table.status() == TableStatus.FAILED)
                    .map(table -> table.sourceDatabase() + "." + table.sourceTable() + "：").toList();
            blockingErrors = blockingErrors.stream().filter(error -> failedLabels.stream().noneMatch(error::startsWith)).toList();
        }
        if (!blockingErrors.isEmpty()) throw new IllegalStateException("启动预检失败：" + String.join("；", blockingErrors));
        if (!resume && (task.status() == TaskStatus.DRAFT || task.status() == TaskStatus.STOPPED || task.status() == TaskStatus.FAILED))
            task.tables().forEach(table -> { coordinator.stop(table.mappingId(), true); poller.reset(table.mappingId()); tasks.clearSnapshotPhase(table.id()); });
        for (var table : prepared.task.tables()) {
            if (resume && task.tables().stream().anyMatch(original -> original.id().equals(table.id()) && original.status() == TableStatus.FAILED)) continue;
            tasks.updateFingerprint(table.id(), table.structureFingerprint(), table.keyColumns());
        }
        tasks.start(id, resume);
        tasks.runtimeTables(id).forEach(table -> delivery.resume(table.mappingId()));
        if (targetSubscriptions != null) targetSubscriptions.initializeForTask(id);
        events.publish("task", Map.of("taskId", id, "action", resume ? "resumed" : "started"));
    }

    public void pause(long id) {
        var task = tasks.task(id);
        tasks.pause(id);
        tasks.runtimeTables(id).forEach(table -> coordinator.stop(table.mappingId(), false));
        tasks.runtimeTables(id).forEach(table -> delivery.suspendAndAwait(table.mappingId()));
        events.publish("task", Map.of("taskId", id, "action", "paused"));
    }

    public void stop(long id) {
        SyncTask task = tasks.task(id);
        tasks.stop(id);
        tasks.runtimeTables(id).forEach(table -> coordinator.stop(table.mappingId(), false));
        task.tables().forEach(table -> { coordinator.stop(table.mappingId(), true); poller.reset(table.mappingId()); tasks.clearSnapshotPhase(table.id()); });
        tasks.runtimeTables(id).forEach(table -> { delivery.suspendAndAwait(table.mappingId()); delivery.cancel(table.mappingId()); });
        events.publish("task", Map.of("taskId", id, "action", "stopped"));
    }

    private Prepared prepare(SyncTask input, boolean preserveFingerprint) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var results = new ArrayList<TableValidation>();
        var source = configs.source(input.sourceId(), true);
        var target = configs.target(input.targetId(), true);
        if (!source.enabled()) errors.add("源连接已停用");
        if (!target.enabled()) errors.add("目标连接已停用");
        boolean targetDatabaseMissing = target.targetType() != TargetType.KAFKA
                && (target.config() == null || target.config().get("database") == null
                || target.config().get("database").toString().isBlank());
        if (targetDatabaseMissing) errors.add("目标连接未配置数据库，请先在账户中填写默认数据库");
        Map<String, List<Map<String, Object>>> sourceTables = new HashMap<>();
        List<Map<String, Object>> targetTables = new ArrayList<>();
        Set<String> topics = new HashSet<>();
        try {
            var probe = connections.probe(source);
            if (!Boolean.TRUE.equals(probe.get("reachable"))) errors.add("源连接不可用：" + probe.getOrDefault("error", "连接失败"));
            else if (!Boolean.TRUE.equals(probe.get("cdcEnabled"))) errors.add("源端 CDC 前置条件未满足：" + probe.getOrDefault("detail", "请检查配置"));
            if (source.dbType() == DatabaseType.STARROCKS) warnings.add("StarRocks 源端使用快照轮询，不保证严格一致");
            for (String database : input.tables().stream().map(SyncTaskTable::sourceDatabase).distinct().toList())
                sourceTables.put(database, connections.tables(source, database));
            if (target.targetType() == TargetType.KAFKA) topics.addAll(connections.topics(target));
            else if (!targetDatabaseMissing)
                for (var table : connections.tables(target)) targetTables.add(table);
        } catch (Exception e) { errors.add(rootMessage(e)); }

        var preparedTables = new ArrayList<SyncTaskTable>();
        var selectedTargets = new HashSet<String>();
        String targetDatabase = target.config() == null ? null : Objects.toString(target.config().get("database"), null);
        for (var table : input.tables()) {
            var tableErrors = new ArrayList<String>();
            var tableWarnings = new ArrayList<String>();
            if (Objects.equals(input.sourceId(), input.targetId())
                    && table.targetSchema() == null && targetDatabase != null
                    && table.sourceDatabase() != null && table.sourceDatabase().equalsIgnoreCase(targetDatabase)
                    && table.sourceTable().equalsIgnoreCase(table.targetTable())) {
                tableErrors.add("源表和目标表不能是同一张表，否则会形成写入回环");
            }
            Map<String, Object> sourceMeta = findTable(sourceTables.getOrDefault(table.sourceDatabase(), List.of()), table.sourceSchema(), table.sourceTable());
            Map<String, Object> targetMeta = target.targetType() == TargetType.KAFKA ? null : findTable(targetTables, table.targetSchema(), table.targetTable());
            List<String> keys = table.keyColumns();
            String fingerprint = table.structureFingerprint();
            if (sourceMeta == null) tableErrors.add("源表不存在");
            else {
                keys = stringList(sourceMeta.get("primaryKeys"));
                if (keys.isEmpty()) {
                    // 大小写只差一个字母的同名表很常见（Orders / orders），只说「没主键」会让人以为系统读错了
                    String sibling = caseSibling(sourceTables.getOrDefault(table.sourceDatabase(), List.of()), table.sourceTable());
                    tableErrors.add("源表缺少主键（元数据未发现 PRIMARY KEY）"
                            + (sibling == null ? "" : "；库中另有同名表 " + sibling + "，请确认选的是哪一张"));
                }
                String current = fingerprint(sourceMeta);
                if (preserveFingerprint && fingerprint != null && !fingerprint.isBlank() && !fingerprint.equals(current))
                    tableErrors.add("源表结构已变化，请重新打开字段映射并保存");
                fingerprint = current;
                if (source.dbType() == DatabaseType.SQLSERVER) {
                    try {
                        if (!connections.cdcEnabledForTable(source, table.sourceDatabase(), table.sourceSchema(), table.sourceTable()))
                            tableErrors.add("SQL Server 源表未启用原生 CDC");
                    } catch (Exception e) { tableErrors.add(rootMessage(e)); }
                }
            }
            if (target.targetType() != TargetType.KAFKA) {
                if (targetMeta == null) tableErrors.add("目标表不存在");
                else if (stringList(targetMeta.get("primaryKeys")).isEmpty()) tableErrors.add("目标表缺少主键");
                if (!selectedTargets.add(key(table.targetSchema(), table.targetTable())))
                    tableErrors.add("目标表已被任务中的其他源表使用");
            } else if (!topics.contains(table.targetTable())) tableErrors.add("Kafka Topic 不存在或不可访问：" + table.targetTable());
            if (sourceMeta != null && targetMeta != null) {
                var sourceColumns = new HashSet<>(stringList(sourceMeta.get("columns")));
                var targetColumns = new HashSet<>(stringList(targetMeta.get("columns")));
                var sourceTypes = columnTypes(sourceMeta);
                var targetTypes = columnTypes(targetMeta);
                table.columns().forEach((from, to) -> {
                    if (!sourceColumns.contains(from)) tableErrors.add("源字段不存在：" + from);
                    if (!targetColumns.contains(to)) tableErrors.add("目标字段不存在：" + to);
                    if (sourceTypes.containsKey(from) && targetTypes.containsKey(to)
                            && !compatible(sourceTypes.get(from), targetTypes.get(to)))
                        tableErrors.add("字段类型不兼容：" + from + " → " + to);
                });
                var targetKeys = new HashSet<>(stringList(targetMeta.get("primaryKeys")));
                if (!keys.isEmpty()) {
                    var mappedKeys = keys.stream().map(key -> table.columns().getOrDefault(key, key)).toList();
                    if (!targetKeys.equals(new HashSet<>(mappedKeys))) tableErrors.add("源主键必须映射到目标表的全部主键字段");
                }
                if (table.includeOperation() && !targetColumns.contains(table.operationColumn())) tableErrors.add("操作类型目标字段不存在");
                if (table.includeCapturedAt() && !targetColumns.contains(table.capturedAtColumn())) tableErrors.add("抓取时间目标字段不存在");
                validateReconciliation(table, sourceTypes, targetTypes, tableErrors, tableWarnings);
            }
            if (sourceMeta != null && target.targetType() == TargetType.KAFKA) {
                var sourceColumns = new HashSet<>(stringList(sourceMeta.get("columns")));
                table.columns().keySet().stream().filter(column -> !sourceColumns.contains(column))
                        .forEach(column -> tableErrors.add("源字段不存在：" + column));
            }
            String label = table.sourceDatabase() + "." + table.sourceTable();
            tableErrors.forEach(error -> errors.add(label + "：" + error));
            tableWarnings.forEach(warning -> warnings.add(label + "：" + warning));
            results.add(new TableValidation(label, tableErrors.isEmpty(), tableErrors, tableWarnings));
            preparedTables.add(copy(table, keys, fingerprint));
        }
        return new Prepared(copy(input, preparedTables), new ValidationReport(errors.isEmpty(), errors, warnings, results));
    }

    private SyncTask copy(SyncTask task, List<SyncTaskTable> tables) {
        return new SyncTask(task.id(), task.name(), task.description(), task.sourceId(), task.targetId(), task.syncMode(),
                task.ddlPolicy(), task.timeZone(), task.status(), task.version(), task.alertLagSeconds(),
                task.reconciliationCron(), task.reconciliationDelayMinutes(), task.reconciliationWindowMinutes(),
                task.reconcileAfterSnapshot(), task.pollIntervalMs(), tables, task.createdAt(), task.updatedAt());
    }

    private SyncTaskTable copy(SyncTaskTable table, List<String> keys, String fingerprint) {
        return new SyncTaskTable(table.id(), table.mappingId(), table.sourceDatabase(), table.sourceSchema(), table.sourceTable(),
                table.targetSchema(), table.targetTable(), table.columns(), keys, table.includeOperation(), table.includeCapturedAt(),
                table.operationColumn(), table.capturedAtColumn(), table.capturedAtFormat(), table.operationValues(),
                table.snapshotProfile(), table.incrementalProfile(), fingerprint, table.reconciliation(), table.status(), table.processedRows(),
                table.errorCount(), table.lastLatencyMs(), table.lastEventAt(), table.lastError());
    }

    private void validateReconciliation(SyncTaskTable table, Map<String, String> sourceTypes,
                                        Map<String, String> targetTypes, List<String> errors, List<String> warnings) {
        var config = table.reconciliation();
        if (config == null || !config.enabled()) return;
        if (config.sourceTimeColumn() == null || !"TIME".equals(family(sourceTypes.getOrDefault(config.sourceTimeColumn(), ""))))
            errors.add("源端核对时间字段必须是日期时间类型");
        if (config.targetTimeColumn() == null || !"TIME".equals(family(targetTypes.getOrDefault(config.targetTimeColumn(), ""))))
            errors.add("目标端核对时间字段必须是日期时间类型");
        if (!Objects.equals(table.columns().get(config.sourceTimeColumn()), config.targetTimeColumn()))
            errors.add("核对时间字段必须存在有效字段映射");
        if (config.fields().isEmpty()) errors.add("请至少选择一个核数字段");
        for (var field : config.fields()) {
            if (!Objects.equals(table.columns().get(field.sourceColumn()), field.targetColumn())) {
                errors.add("核数字段未正确映射：" + field.sourceColumn()); continue;
            }
            String detected = family(sourceTypes.getOrDefault(field.sourceColumn(), ""));
            String expected = switch (field.logicalType()) {
                case NUMBER -> "NUMBER"; case STRING -> "TEXT"; case DATETIME -> "TIME";
                case BOOLEAN -> "BOOLEAN"; case OTHER -> detected;
            };
            if (!detected.isBlank() && !detected.equals(expected)) errors.add("核数字段逻辑类型不匹配：" + field.sourceColumn());
            var allowed = switch (field.logicalType()) {
                case NUMBER -> Set.of(ReconciliationMetric.SUM, ReconciliationMetric.MIN, ReconciliationMetric.MAX, ReconciliationMetric.NULL_RATE);
                case STRING, OTHER -> Set.of(ReconciliationMetric.NULL_RATE);
                case DATETIME -> Set.of(ReconciliationMetric.MIN, ReconciliationMetric.MAX, ReconciliationMetric.NULL_RATE);
                case BOOLEAN -> Set.of(ReconciliationMetric.TRUE_COUNT, ReconciliationMetric.FALSE_COUNT, ReconciliationMetric.NULL_RATE);
            };
            if (field.metrics().stream().anyMatch(metric -> !allowed.contains(metric)))
                errors.add("核数字段包含不兼容指标：" + field.sourceColumn());
        }
        warnings.add("建议为核对时间字段建立索引，避免增量窗口扫描影响业务库");
    }

    /**
     * 按 schema + 表名查元数据。
     *
     * <p><b>必须先精确匹配</b>：MySQL 在 Linux 上表名区分大小写，`Orders` 与 `orders` 是两张不同的表。
     * 直接用小写 key 做 findFirst 会把小写表匹配到大写表的元数据上——表现为「明明有主键却说没有」。
     * 精确匹配落空时才退回忽略大小写，兼容 SQL Server 等大小写不敏感的来源。
     */
    private Map<String, Object> findTable(List<Map<String, Object>> tables, String schema, String table) {
        for (Map<String, Object> item : tables) {
            if (Objects.equals(schema, item.get("schema")) && table.equals(String.valueOf(item.get("table")))) {
                return item;
            }
        }
        return tables.stream().filter(item -> Objects.equals(key(schema, table), tableKey(item))).findFirst().orElse(null);
    }
    private String tableKey(Map<String, Object> table) { return key(Objects.toString(table.get("schema"), null), String.valueOf(table.get("table"))); }
    private String key(String schema, String table) { return (schema == null || schema.isBlank() ? "" : schema.toLowerCase(Locale.ROOT) + ".") + table.toLowerCase(Locale.ROOT); }
    private List<String> stringList(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private Map<String, String> columnTypes(Map<String, Object> metadata) {
        var result = new HashMap<String, String>();
        Object details = metadata.get("columnDetails");
        if (details instanceof List<?> list)
            for (Object item : list) if (item instanceof Map<?, ?> column)
                result.put(String.valueOf(column.get("name")), String.valueOf(column.get("type")));
        return result;
    }
    private boolean compatible(String source, String target) {
        return family(source).equals(family(target)) || source.isBlank() || target.isBlank();
    }
    private String family(String type) {
        String value = type.toUpperCase(Locale.ROOT);
        if (value.matches(".*(INT|DECIMAL|NUMERIC|NUMBER|FLOAT|DOUBLE|REAL).*")) return "NUMBER";
        if (value.matches(".*(CHAR|TEXT|STRING|JSON).*")) return "TEXT";
        if (value.matches(".*(DATE|TIME|YEAR).*")) return "TIME";
        if (value.matches(".*(BOOL|BIT).*")) return "BOOLEAN";
        if (value.matches(".*(BINARY|BLOB|VARBINARY).*")) return "BINARY";
        return value;
    }
    /**
     * 找同名但仅大小写不同的兄弟表（Orders / orders）。
     *
     * <p>MySQL 在 Linux 上默认区分表名大小写，这种成对的表很常见；用户选错时，
     * 光说「没有主键」会让人以为系统读错了，必须指出另一张表的存在。
     */
    private String caseSibling(List<Map<String, Object>> tables, String table) {
        for (Map<String, Object> candidate : tables) {
            String name = String.valueOf(candidate.get("table"));
            List<String> keys = stringList(candidate.get("primaryKeys"));
            if (!name.equals(table) && name.equalsIgnoreCase(table) && !keys.isEmpty()) {
                return name + "（主键 " + String.join(", ", keys) + "）";
            }
        }
        return null;
    }

    private String fingerprint(Map<String, Object> metadata) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(canonical(metadata)).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }
    private String rootMessage(Throwable e) { var t=e; while(t.getCause()!=null)t=t.getCause(); return Objects.toString(t.getMessage(), t.getClass().getSimpleName()); }

    private record Prepared(SyncTask task, ValidationReport report) {}
    public record ValidationReport(boolean valid, List<String> errors, List<String> warnings, List<TableValidation> tables) {}
    public record TableValidation(String table, boolean valid, List<String> errors, List<String> warnings) {}
}
