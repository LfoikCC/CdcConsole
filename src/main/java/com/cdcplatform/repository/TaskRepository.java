package com.cdcplatform.repository;

import com.cdcplatform.model.*;
import com.cdcplatform.model.OperationsModels.*;
import com.cdcplatform.model.Types.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Repository
public class TaskRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ConfigRepository configs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher publisher;
    public record TaskLogWritten(long taskId) {}
    public record SamplingReset(long taskId) {}

    public TaskRepository(JdbcClient jdbc, ObjectMapper json, ConfigRepository configs) {
        this.jdbc = jdbc;
        this.json = json;
        this.configs = configs;
    }

    public List<SyncTask> tasks() {
        return jdbc.sql("SELECT * FROM sync_task ORDER BY updated_at DESC,id DESC")
                .query((rs, row) -> readTask(rs.getLong("id"), rs)).list();
    }

    public List<SyncTask> samplingTasks() {
        return jdbc.sql("SELECT * FROM sync_task WHERE status IN ('STARTING','SNAPSHOTTING','RUNNING','DEGRADED') ORDER BY id")
                .query((rs, row) -> readTask(rs.getLong("id"), rs)).list();
    }

    public SyncTask task(long id) {
        return jdbc.sql("SELECT * FROM sync_task WHERE id=:id").param("id", id)
                .query((rs, row) -> readTask(id, rs)).optional()
                .orElseThrow(() -> notFound("task", id));
    }

    public SyncTaskTable tableForMapping(long mappingId) {
        return jdbc.sql("SELECT * FROM sync_task_table WHERE mapping_id=:id").param("id", mappingId)
                .query(this::readTable).optional().orElse(null);
    }

    public SyncTaskTable table(long tableId) {
        return jdbc.sql("SELECT * FROM sync_task_table WHERE id=:id").param("id", tableId)
                .query(this::readTable).optional().orElseThrow(() -> notFound("task table", tableId));
    }

    /** Includes internal target-subscription rows used by the runtime. */
    public List<SyncTaskTable> runtimeTables(long taskId) {
        return jdbc.sql("SELECT * FROM sync_task_table WHERE task_id=:id ORDER BY id").param("id", taskId)
                .query(this::readTable).list();
    }

    public Long taskIdForMapping(long mappingId) {
        return jdbc.sql("SELECT task_id FROM sync_task_table WHERE mapping_id=:id").param("id", mappingId)
                .query(Long.class).optional().orElse(null);
    }

    public SyncMode syncModeForMapping(long mappingId) {
        return jdbc.sql("SELECT t.sync_mode FROM sync_task t JOIN sync_task_table st ON st.task_id=t.id WHERE st.mapping_id=:id")
                .param("id", mappingId).query(String.class).optional().map(SyncMode::valueOf)
                .orElse(SyncMode.INCREMENTAL_ONLY);
    }

    @Transactional
    public long create(SyncTask value) {
        validate(value, null);
        long id = nextId();
        jdbc.sql("INSERT INTO sync_task(id,name,description,source_id,target_id,sync_mode,ddl_policy,time_zone,status,version," +
                        "alert_lag_seconds,reconciliation_cron,reconciliation_delay_minutes,reconciliation_window_minutes,reconcile_after_snapshot) " +
                        "VALUES(:id,:name,:description,:source,:target,:mode,:ddl,:zone,:status,0,:lag,:cron,:delay,:window,:afterSnapshot)")
                .param("id", id).param("name", value.name().trim()).param("description", value.description())
                .param("source", value.sourceId()).param("target", value.targetId()).param("mode", value.syncMode().name())
                .param("ddl", value.ddlPolicy().name()).param("zone", value.timeZone()).param("status", TaskStatus.DRAFT.name())
                .param("lag", value.alertLagSeconds()).param("cron", blankToNull(value.reconciliationCron()))
                .param("delay", value.reconciliationDelayMinutes()).param("window", value.reconciliationWindowMinutes())
                .param("afterSnapshot", value.reconcileAfterSnapshot()).update();
        insertTables(id, value);
        addLog(id, null, "INFO", "任务已创建，等待启动");
        return id;
    }

    @Transactional
    public void update(long id, SyncTask value) {
        SyncTask current = task(id);
        if (current.status() != TaskStatus.DRAFT && current.status() != TaskStatus.STOPPED)
            throw new IllegalStateException("运行中的任务不能修改配置，请先停止任务");
        if (!Objects.equals(current.version(), value.version()))
            throw new IllegalStateException("任务配置已被其他页面更新，请刷新后重试");
        validate(value, id);
        var mappingIds = current.tables().stream().map(SyncTaskTable::mappingId).filter(Objects::nonNull).toList();
        int changed = jdbc.sql("UPDATE sync_task SET name=:name,description=:description,source_id=:source,target_id=:target," +
                        "sync_mode=:mode,ddl_policy=:ddl,time_zone=:zone,alert_lag_seconds=:lag,reconciliation_cron=:cron," +
                        "reconciliation_delay_minutes=:delay,reconciliation_window_minutes=:window,reconcile_after_snapshot=:afterSnapshot," +
                        "version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status IN ('DRAFT','STOPPED')")
                .param("id", id).param("version", value.version()).param("name", value.name().trim())
                .param("description", value.description()).param("source", value.sourceId()).param("target", value.targetId())
                .param("mode", value.syncMode().name()).param("ddl", value.ddlPolicy().name()).param("zone", value.timeZone())
                .param("lag", value.alertLagSeconds()).param("cron", blankToNull(value.reconciliationCron()))
                .param("delay", value.reconciliationDelayMinutes()).param("window", value.reconciliationWindowMinutes())
                .param("afterSnapshot", value.reconcileAfterSnapshot()).update();
        if (changed != 1) throw new IllegalStateException("任务配置已发生变化，请刷新后重试");
        jdbc.sql("DELETE FROM sync_task_table WHERE task_id=:id").param("id", id).update();
        mappingIds.forEach(configs::deleteMapping);
        insertTables(id, value);
        addLog(id, null, "INFO", "任务配置已更新");
    }

    public void rename(long id, String name, String description) {
        task(id);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("任务名称不能为空");
        jdbc.sql("UPDATE sync_task SET name=:name,description=:description,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("name", name.trim()).param("description", description == null ? "" : description).update();
    }

    @Transactional
    public void delete(long id) {
        SyncTask task = task(id);
        if (task.status() != TaskStatus.DRAFT && task.status() != TaskStatus.STOPPED)
            throw new IllegalStateException("请先停止任务再删除");
        var mappingIds = task.tables().stream().map(SyncTaskTable::mappingId).filter(Objects::nonNull).toList();
        jdbc.sql("DELETE FROM sync_task WHERE id=:id").param("id", id).update();
        mappingIds.forEach(configs::deleteMapping);
    }

    @Transactional
    public long start(long id, boolean resume) {
        SyncTask task = task(id);
        if (resume && task.status() != TaskStatus.PAUSED) throw new IllegalStateException("只有暂停中的任务可以恢复");
        if (!resume && task.status() != TaskStatus.DRAFT && task.status() != TaskStatus.STOPPED && task.status() != TaskStatus.FAILED)
            throw new IllegalStateException("当前任务状态不能启动");
        TaskStatus next = task.syncMode() == SyncMode.FULL_INCREMENTAL && !resume ? TaskStatus.SNAPSHOTTING : TaskStatus.STARTING;
        setTaskStatus(id, next);
        if (publisher != null) publisher.publishEvent(new SamplingReset(id));
        if (resume) {
            for (var table : task.tables()) {
                if (table.status() == TableStatus.FAILED) continue;
                configs.setMappingEnabled(table.mappingId(), true);
                updateTableStatus(table.id(), TableStatus.STARTING, null);
            }
        } else setMappingsEnabled(task, true, TableStatus.STARTING);
        long instanceId = nextId();
        jdbc.sql("INSERT INTO task_instance(id,task_id,status,message) VALUES(:id,:task,:status,:message)")
                .param("id", instanceId).param("task", id).param("status", next.name())
                .param("message", resume ? "从 checkpoint 恢复" : "任务启动").update();
        for (var table : task.tables()) jdbc.sql("INSERT INTO table_instance(id,instance_id,task_table_id,status,ended_at) " +
                        "VALUES(:id,:instance,:table,:status,:ended)")
                .param("id", nextId()).param("instance", instanceId).param("table", table.id())
                .param("status", resume && table.status() == TableStatus.FAILED ? TableStatus.FAILED.name() : TableStatus.STARTING.name())
                .param("ended", resume && table.status() == TableStatus.FAILED ? OffsetDateTime.now() : null).update();
        addLog(id, null, "INFO", resume ? "任务已恢复" : "任务已启动");
        return instanceId;
    }

    @Transactional
    public void pause(long id) {
        SyncTask task = task(id);
        if (!Set.of(TaskStatus.RUNNING, TaskStatus.DEGRADED, TaskStatus.STARTING, TaskStatus.SNAPSHOTTING).contains(task.status()))
            throw new IllegalStateException("当前任务状态不能暂停");
        for (var table : task.tables()) {
            configs.setMappingEnabled(table.mappingId(), false);
            if (table.status() != TableStatus.FAILED) updateTableStatus(table.id(), TableStatus.PAUSED, null);
        }
        setTaskStatus(id, TaskStatus.PAUSED);
        closeOpenInstance(id, "PAUSED", "任务已暂停，checkpoint 已保留");
        addLog(id, null, "INFO", "任务已暂停，checkpoint 已保留");
    }

    @Transactional
    public void stop(long id) {
        SyncTask task = task(id);
        if (task.status() == TaskStatus.DRAFT || task.status() == TaskStatus.STOPPED)
            throw new IllegalStateException("当前任务状态不能停止");
        setMappingsEnabled(task, false, TableStatus.STOPPED);
        setTaskStatus(id, TaskStatus.STOPPED);
        closeOpenInstance(id, "STOPPED", "任务已停止");
        addLog(id, null, "INFO", "任务已停止，同步位点已重置");
    }

    public void updateRuntimeStatus(long id, TaskStatus status) {
        setTaskStatus(id, status);
        if (status == TaskStatus.FAILED) closeOpenInstance(id, "FAILED", "所有表均已失败");
        else jdbc.sql("UPDATE task_instance SET status=:status WHERE task_id=:id AND ended_at IS NULL")
                .param("id", id).param("status", status.name()).update();
    }

    public void updateTableStatus(long tableId, TableStatus status, String error) {
        jdbc.sql("UPDATE sync_task_table SET status=:status,last_error=:error WHERE id=:id")
                .param("id", tableId).param("status", status.name()).param("error", error).update();
        jdbc.sql("UPDATE table_instance SET status=:status,message=:error,ended_at=" +
                        "CASE WHEN :terminal THEN CURRENT_TIMESTAMP ELSE ended_at END " +
                        "WHERE task_table_id=:id AND ended_at IS NULL")
                .param("id", tableId).param("status", status.name()).param("error", error)
                .param("terminal", Set.of(TableStatus.FAILED, TableStatus.PAUSED, TableStatus.STOPPED).contains(status)).update();
    }

    public void updateFingerprint(long tableId, String fingerprint, List<String> keys) {
        jdbc.sql("UPDATE sync_task_table SET structure_fingerprint=:fingerprint,key_columns_json=:keys WHERE id=:id")
                .param("id", tableId).param("fingerprint", fingerprint).param("keys", write(keys)).update();
    }

    public String snapshotStartPosition(long tableId) {
        return jdbc.sql("SELECT position_json FROM sync_checkpoint WHERE task_table_id=:id AND phase='SNAPSHOTTING'")
                .param("id", tableId).query(String.class).optional().orElse(null);
    }

    public void beginSnapshot(long tableId, String position) {
        int changed = jdbc.sql("UPDATE sync_checkpoint SET phase='SNAPSHOTTING',position_json=:position,updated_at=CURRENT_TIMESTAMP WHERE task_table_id=:id")
                .param("id", tableId).param("position", position).update();
        if (changed == 0) jdbc.sql("INSERT INTO sync_checkpoint(task_table_id,phase,position_json) VALUES(:id,'SNAPSHOTTING',:position)")
                .param("id", tableId).param("position", position).update();
    }

    public void completeSnapshot(long tableId, String position) {
        jdbc.sql("UPDATE sync_checkpoint SET phase='STREAMING',position_json=:position,updated_at=CURRENT_TIMESTAMP WHERE task_table_id=:id")
                .param("id", tableId).param("position", position).update();
    }

    public void clearSnapshotPhase(long tableId) {
        jdbc.sql("DELETE FROM sync_checkpoint WHERE task_table_id=:id").param("id", tableId).update();
    }

    public void recordDelivery(long mappingId, long latencyMs) {
        jdbc.sql("UPDATE sync_task_table SET processed_rows=processed_rows+1,last_latency_ms=:latency,last_event_at=CURRENT_TIMESTAMP," +
                        "last_error=NULL,status='RUNNING' WHERE mapping_id=:id")
                .param("id", mappingId).param("latency", Math.max(0, latencyMs)).update();
    }

    public void updateSourceLag(long mappingId, long lagMs) {
        jdbc.sql("UPDATE sync_task_table SET last_latency_ms=:lag WHERE mapping_id=:id")
                .param("id", mappingId).param("lag", Math.max(0, lagMs)).update();
    }

    public void recordFailure(long mappingId, String message) {
        jdbc.sql("UPDATE sync_task_table SET error_count=error_count+1,last_error=:error,status='FAILED' WHERE mapping_id=:id")
                .param("id", mappingId).param("error", truncate(message, 1900)).update();
        jdbc.sql("UPDATE table_instance SET status='FAILED',message=:error,ended_at=CURRENT_TIMESTAMP " +
                        "WHERE task_table_id=(SELECT id FROM sync_task_table WHERE mapping_id=:id) AND ended_at IS NULL")
                .param("id", mappingId).param("error", truncate(message, 1900)).update();
    }

    public void addLog(long taskId, Long tableId, String level, String message) {
        jdbc.sql("INSERT INTO runtime_log(id,task_id,table_id,level,message) VALUES(:id,:task,:table,:level,:message)")
                .param("id", nextId()).param("task", taskId).param("table", tableId).param("level", level)
                .param("message", truncate(message, 3900)).update();
        if (publisher != null) publisher.publishEvent(new TaskLogWritten(taskId));
    }

    public List<RuntimeLog> logs(long taskId) {
        task(taskId);
        return jdbc.sql("SELECT * FROM runtime_log WHERE task_id=:id ORDER BY created_at DESC,id DESC LIMIT 500")
                .param("id", taskId).query((rs, row) -> new RuntimeLog(rs.getLong("id"), taskId,
                        nullableLong(rs, "table_id"), rs.getString("level"), rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public PageResult<RuntimeLog> logs(long taskId, int page, int size) {
        task(taskId);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 100));
        long total = jdbc.sql("SELECT COUNT(*) FROM runtime_log WHERE task_id=:id")
                .param("id", taskId).query(Long.class).single();
        var items = jdbc.sql("SELECT * FROM runtime_log WHERE task_id=:id ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset")
                .param("id", taskId).param("size", safeSize).param("offset", (long) safePage * safeSize)
                .query((rs, row) -> new RuntimeLog(rs.getLong("id"), taskId,
                        nullableLong(rs, "table_id"), rs.getString("level"), rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
        return new PageResult<>(items, total, safePage, safeSize,
                total == 0 ? 0 : (int) Math.ceil((double) total / safeSize));
    }

    public List<TaskInstance> instances(long taskId) {
        task(taskId);
        return jdbc.sql("SELECT * FROM task_instance WHERE task_id=:id ORDER BY started_at DESC,id DESC LIMIT 200")
                .param("id", taskId).query((rs, row) -> new TaskInstance(rs.getLong("id"), taskId,
                        rs.getString("status"), rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("ended_at", OffsetDateTime.class), rs.getString("message"))).list();
    }

    public OffsetDateTime latestRunAt(long taskId) {
        return jdbc.sql("SELECT MAX(started_at) FROM task_instance WHERE task_id=:id")
                .param("id", taskId).query(OffsetDateTime.class).optional().orElse(null);
    }

    public long createAlert(long taskId, Long tableId, AlertType type, String title, String message) {
        var existing = jdbc.sql("SELECT id FROM alert_event WHERE task_id=:task AND alert_type=:type AND status='OPEN' " +
                        "AND ((table_id IS NULL AND :table IS NULL) OR table_id=:table) ORDER BY created_at DESC LIMIT 1")
                .param("task", taskId).param("type", type.name()).param("table", tableId).query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        long id = nextId();
        jdbc.sql("INSERT INTO alert_event(id,task_id,table_id,alert_type,status,title,message) VALUES(:id,:task,:table,:type,'OPEN',:title,:message)")
                .param("id", id).param("task", taskId).param("table", tableId).param("type", type.name())
                .param("title", title).param("message", truncate(message, 3900)).update();
        return id;
    }

    public List<AlertEvent> alerts(AlertStatus status) {
        String sql = "SELECT * FROM alert_event" + (status == null ? "" : " WHERE status=:status") + " ORDER BY created_at DESC,id DESC";
        var statement = jdbc.sql(sql);
        if (status != null) statement.param("status", status.name());
        return statement.query((rs, row) -> new AlertEvent(rs.getLong("id"), rs.getLong("task_id"),
                nullableLong(rs, "table_id"), AlertType.valueOf(rs.getString("alert_type")), AlertStatus.valueOf(rs.getString("status")),
                rs.getString("title"), rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    public void updateAlert(long id, AlertStatus status) {
        int changed = jdbc.sql("UPDATE alert_event SET status=:status,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status.name()).update();
        if (changed != 1) throw notFound("alert", id);
    }

    public void sampleMetrics(long taskId, long queueDepth, double readRate, double writeRate) {
        var task = task(taskId);
        long rows = task.tables().stream().mapToLong(SyncTaskTable::processedRows).sum();
        long errors = task.tables().stream().mapToLong(SyncTaskTable::errorCount).sum();
        long lag = task.tables().stream().map(SyncTaskTable::lastLatencyMs).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0);
        jdbc.sql("INSERT INTO metric_sample(id,task_id,processed_rows,error_count,lag_ms,queue_depth,read_rate,write_rate) " +
                        "VALUES(:id,:task,:rows,:errors,:lag,:queue,:read,:write)")
                .param("id", nextId()).param("task", taskId).param("rows", rows).param("errors", errors)
                .param("lag", lag).param("queue", queueDepth).param("read", readRate).param("write", writeRate).update();
    }

    public List<MetricSample> metrics(long taskId, OffsetDateTime since) {
        task(taskId);
        return jdbc.sql("SELECT * FROM metric_sample WHERE task_id=:task AND sampled_at>=:since ORDER BY sampled_at")
                .param("task", taskId).param("since", since).query((rs, row) -> new MetricSample(rs.getLong("id"), taskId,
                        rs.getObject("sampled_at", OffsetDateTime.class), rs.getLong("processed_rows"), rs.getLong("error_count"),
                        rs.getLong("lag_ms"), rs.getLong("queue_depth"), rs.getDouble("read_rate"), rs.getDouble("write_rate"))).list();
    }

    public PageResult<MetricSample> metrics(long taskId, OffsetDateTime since, int page, int size) {
        task(taskId);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 100));
        long total = jdbc.sql("SELECT COUNT(*) FROM metric_sample WHERE task_id=:task AND sampled_at>=:since")
                .param("task", taskId).param("since", since).query(Long.class).single();
        var items = jdbc.sql("SELECT * FROM metric_sample WHERE task_id=:task AND sampled_at>=:since " +
                        "ORDER BY sampled_at,id LIMIT :size OFFSET :offset")
                .param("task", taskId).param("since", since).param("size", safeSize)
                .param("offset", (long) safePage * safeSize)
                .query((rs, row) -> new MetricSample(rs.getLong("id"), taskId,
                        rs.getObject("sampled_at", OffsetDateTime.class), rs.getLong("processed_rows"), rs.getLong("error_count"),
                        rs.getLong("lag_ms"), rs.getLong("queue_depth"), rs.getDouble("read_rate"), rs.getDouble("write_rate"))).list();
        return new PageResult<>(items, total, safePage, safeSize,
                total == 0 ? 0 : (int) Math.ceil((double) total / safeSize));
    }

    public void cleanMetrics(OffsetDateTime before) {
        jdbc.sql("DELETE FROM metric_sample WHERE sampled_at<:before").param("before", before).update();
    }

    public long beginReconciliation(long taskId) {
        task(taskId);
        long id = nextId();
        jdbc.sql("INSERT INTO reconciliation_run(id,task_id,status) VALUES(:id,:task,'RUNNING')")
                .param("id", id).param("task", taskId).update();
        return id;
    }

    public void finishReconciliation(long id, long sourceRows, long targetRows, long mismatches,
                                     List<String> keys, String status, String message) {
        jdbc.sql("UPDATE reconciliation_run SET status=:status,source_rows=:source,target_rows=:target,mismatch_count=:mismatch," +
                        "sample_keys_json=:keys,message=:message,ended_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status).param("source", sourceRows).param("target", targetRows)
                .param("mismatch", mismatches).param("keys", write(keys)).param("message", message).update();
    }

    public void addReconciliationDetail(long runId, long tableId, long sourceRows, long targetRows,
                                        long mismatches, List<String> keys) {
        jdbc.sql("INSERT INTO reconciliation_detail(id,run_id,task_table_id,source_rows,target_rows,mismatch_count,sample_keys_json) " +
                        "VALUES(:id,:run,:table,:source,:target,:mismatch,:keys)")
                .param("id", nextId()).param("run", runId).param("table", tableId).param("source", sourceRows)
                .param("target", targetRows).param("mismatch", mismatches).param("keys", write(keys)).update();
    }

    public List<ReconciliationRun> reconciliations(long taskId) {
        task(taskId);
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE task_id=:task ORDER BY started_at DESC,id DESC LIMIT 100")
                .param("task", taskId).query((rs, row) -> new ReconciliationRun(rs.getLong("id"), taskId,
                        rs.getString("status"), rs.getString("mode"), rs.getString("trigger_source"),
                        rs.getObject("window_start", OffsetDateTime.class), rs.getObject("window_end", OffsetDateTime.class),
                        rs.getLong("source_rows"), rs.getLong("target_rows"),
                        rs.getLong("mismatch_count"), readList(rs.getString("sample_keys_json")), rs.getString("message"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class))).list();
    }

    private SyncTask readTask(long id, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SyncTask(id, rs.getString("name"), rs.getString("description"), rs.getLong("source_id"), rs.getLong("target_id"),
                SyncMode.valueOf(rs.getString("sync_mode")), DdlPolicy.valueOf(rs.getString("ddl_policy")), rs.getString("time_zone"),
                TaskStatus.valueOf(rs.getString("status")), rs.getInt("version"), rs.getInt("alert_lag_seconds"),
                rs.getString("reconciliation_cron"), rs.getInt("reconciliation_delay_minutes"),
                rs.getInt("reconciliation_window_minutes"), rs.getBoolean("reconcile_after_snapshot"), tables(id),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private List<SyncTaskTable> tables(long taskId) {
        // Subscription delivery rows are an implementation detail. The task API
        // exposes each source table once and target-specific mappings separately.
        return jdbc.sql("SELECT * FROM sync_task_table WHERE task_id=:id AND subscription_id IS NULL ORDER BY id").param("id", taskId)
                .query(this::readTable).list();
    }

    private SyncTaskTable readTable(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SyncTaskTable(rs.getLong("id"), rs.getLong("mapping_id"), rs.getString("source_database"),
                rs.getString("source_schema"), rs.getString("source_table"), rs.getString("target_schema"), rs.getString("target_table"),
                readMap(rs.getString("columns_json")), readList(rs.getString("key_columns_json")), rs.getBoolean("include_operation"),
                rs.getBoolean("include_captured_at"), rs.getString("operation_column"), rs.getString("captured_at_column"),
                rs.getString("captured_at_format"), readMap(rs.getString("operation_values_json")),
                SnapshotProfile.valueOf(rs.getString("snapshot_profile")), IncrementalProfile.valueOf(rs.getString("incremental_profile")),
                rs.getString("structure_fingerprint"), readReconciliation(rs.getString("reconciliation_json")),
                TableStatus.valueOf(rs.getString("status")), rs.getLong("processed_rows"),
                rs.getLong("error_count"), nullableLong(rs, "last_latency_ms"), rs.getObject("last_event_at", OffsetDateTime.class),
                rs.getString("last_error"));
    }

    private void insertTables(long taskId, SyncTask task) {
        for (var table : task.tables()) {
            var mapping = new TableMapping(null, task.sourceId(), task.targetId(), table.sourceDatabase(), table.sourceSchema(),
                    table.sourceTable(), table.targetSchema(), table.targetTable(), table.columns(), table.includeOperation(),
                    table.includeCapturedAt(), table.operationColumn(), table.capturedAtColumn(), table.capturedAtFormat(),
                    table.operationValues(), false);
            long mappingId = configs.addMapping(mapping);
            long tableId = nextId();
            jdbc.sql("INSERT INTO sync_task_table(id,task_id,mapping_id,source_database,source_schema,source_table,target_schema,target_table," +
                            "columns_json,key_columns_json,include_operation,include_captured_at,operation_column,captured_at_column,captured_at_format," +
                            "operation_values_json,snapshot_profile,incremental_profile,structure_fingerprint,reconciliation_json,status) " +
                            "VALUES(:id,:task,:mapping,:sd,:ss,:st,:ts,:tt,:columns,:keys,:operation,:captured,:operationColumn,:capturedColumn," +
                            ":capturedFormat,:operationValues,:snapshotProfile,:incrementalProfile,:fingerprint,:reconciliation,'DRAFT')")
                    .param("id", tableId).param("task", taskId).param("mapping", mappingId).param("sd", table.sourceDatabase())
                    .param("ss", blankToNull(table.sourceSchema())).param("st", table.sourceTable()).param("ts", blankToNull(table.targetSchema()))
                    .param("tt", table.targetTable()).param("columns", write(table.columns())).param("keys", write(table.keyColumns()))
                    .param("operation", table.includeOperation()).param("captured", table.includeCapturedAt())
                    .param("operationColumn", table.operationColumn()).param("capturedColumn", table.capturedAtColumn())
                    .param("capturedFormat", table.capturedAtFormat()).param("operationValues", write(table.operationValues()))
                    .param("snapshotProfile", table.snapshotProfile().name()).param("incrementalProfile", table.incrementalProfile().name())
                    .param("fingerprint", blankToNull(table.structureFingerprint())).param("reconciliation", write(table.reconciliation())).update();
        }
    }

    private void validate(SyncTask task, Long id) {
        configs.source(task.sourceId(), false);
        configs.target(task.targetId(), false);
        ZoneId.of(task.timeZone());
        if (task.tables() == null || task.tables().isEmpty()) throw new IllegalArgumentException("至少选择一张源表");
        var sources = new HashSet<String>();
        for (var table : task.tables()) {
            String key = table.sourceDatabase() + "." + Objects.toString(table.sourceSchema(), "") + "." + table.sourceTable();
            if (!sources.add(key.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("源表重复：" + key);
            var targets = new HashSet<>(table.columns().values());
            if (targets.size() != table.columns().size()) throw new IllegalArgumentException("同一目标字段不能被重复映射");
            if (table.includeOperation() && targets.contains(table.operationColumn()))
                throw new IllegalArgumentException("操作类型字段不能与普通字段映射重复");
            if (table.includeCapturedAt() && targets.contains(table.capturedAtColumn()))
                throw new IllegalArgumentException("抓取时间字段不能与普通字段映射重复");
            validateReconciliation(table);
        }
    }

    private void validateReconciliation(SyncTaskTable table) {
        var config = table.reconciliation();
        if (config == null || !config.enabled()) return;
        if (config.sourceTimeColumn() == null || config.sourceTimeColumn().isBlank())
            throw new IllegalArgumentException(table.sourceTable() + "：请选择源端业务变更时间字段");
        if (config.targetTimeColumn() == null || config.targetTimeColumn().isBlank())
            throw new IllegalArgumentException(table.sourceTable() + "：请选择目标端业务变更时间字段");
        String mappedTime = table.columns().get(config.sourceTimeColumn());
        if (!Objects.equals(mappedTime, config.targetTimeColumn()))
            throw new IllegalArgumentException(table.sourceTable() + "：核对时间字段必须存在有效字段映射");
        if (config.fields().isEmpty()) throw new IllegalArgumentException(table.sourceTable() + "：请至少选择一个核数字段");
        if (table.keyColumns().isEmpty()) throw new IllegalArgumentException(table.sourceTable() + "：数据核对要求源表主键");
        for (var key : table.keyColumns()) if (!table.columns().containsKey(key))
            throw new IllegalArgumentException(table.sourceTable() + "：主键字段未映射：" + key);
        var seen = new HashSet<String>();
        for (var field : config.fields()) {
            if (!Objects.equals(table.columns().get(field.sourceColumn()), field.targetColumn()))
                throw new IllegalArgumentException(table.sourceTable() + "：核数字段未正确映射：" + field.sourceColumn());
            if (!seen.add(field.sourceColumn())) throw new IllegalArgumentException(table.sourceTable() + "：核数字段重复：" + field.sourceColumn());
            if (field.sourceColumn().equals(table.operationColumn()) || field.sourceColumn().equals(table.capturedAtColumn())
                    || field.targetColumn().equals(table.operationColumn()) || field.targetColumn().equals(table.capturedAtColumn()))
                throw new IllegalArgumentException(table.sourceTable() + "：CDC 扩展字段不能作为业务核数字段");
        }
    }

    private void setMappingsEnabled(SyncTask task, boolean enabled, TableStatus tableStatus) {
        for (var table : runtimeTables(task.id())) {
            configs.setMappingEnabled(table.mappingId(), enabled);
            updateTableStatus(table.id(), tableStatus, null);
        }
    }

    private void setTaskStatus(long id, TaskStatus status) {
        jdbc.sql("UPDATE sync_task SET status=:status,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status.name()).update();
        if (publisher != null && !Set.of(TaskStatus.STARTING, TaskStatus.SNAPSHOTTING, TaskStatus.RUNNING, TaskStatus.DEGRADED).contains(status))
            publisher.publishEvent(new SamplingReset(id));
    }

    private void closeOpenInstance(long taskId, String status, String message) {
        jdbc.sql("UPDATE task_instance SET status=:status,message=:message,ended_at=CURRENT_TIMESTAMP " +
                        "WHERE task_id=:task AND ended_at IS NULL")
                .param("task", taskId).param("status", status).param("message", message).update();
    }

    private long nextId() { return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single(); }
    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String truncate(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    private Map<String, String> readMap(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); } }
    private List<String> readList(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ReconciliationConfig readReconciliation(String value) {
        if (value == null || value.isBlank()) return ReconciliationConfig.disabled();
        try { return json.readValue(value, ReconciliationConfig.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid JSON", e); } }
    private IllegalArgumentException notFound(String type, long id) { return new IllegalArgumentException(type + " " + id + " not found"); }
}
