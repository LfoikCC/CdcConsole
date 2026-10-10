package com.cdcplatform.repository;

import com.cdcplatform.model.OperationsModels.*;
import com.cdcplatform.model.PageResult;
import com.cdcplatform.model.Types.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class ReconciliationRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ReconciliationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    public long begin(long taskId, ReconciliationMode mode, ReconciliationTrigger trigger,
                      OffsetDateTime start, OffsetDateTime end, List<Long> tableIds) {
        return begin(taskId, null, mode, trigger, start, end, tableIds);
    }

    public long begin(long taskId, Long subscriptionId, ReconciliationMode mode, ReconciliationTrigger trigger,
                      OffsetDateTime start, OffsetDateTime end, List<Long> tableIds) {
        long id = nextId();
        jdbc.sql("INSERT INTO reconciliation_run(id,task_id,subscription_id,status,mode,trigger_source,window_start,window_end,requested_table_ids_json) " +
                        "VALUES(:id,:task,:subscription,'QUEUED',:mode,:trigger,:start,:end,:tables)")
                .param("id", id).param("task", taskId).param("mode", mode.name()).param("trigger", trigger.name())
                .param("subscription", subscriptionId)
                .param("start", start).param("end", end).param("tables", write(tableIds)).update();
        return id;
    }

    public OptionalLong existing(long taskId, ReconciliationMode mode, OffsetDateTime start, OffsetDateTime end) {
        var ids = jdbc.sql("SELECT id FROM reconciliation_run WHERE task_id=:task AND mode=:mode " +
                        "AND ((window_start IS NULL AND :start IS NULL) OR window_start=:start) " +
                        "AND ((window_end IS NULL AND :end IS NULL) OR window_end=:end) ORDER BY id DESC LIMIT 1")
                .param("task", taskId).param("mode", mode.name()).param("start", start).param("end", end)
                .query(Long.class).list();
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    public OptionalLong existing(long taskId, long subscriptionId, ReconciliationMode mode, OffsetDateTime start, OffsetDateTime end) {
        var ids=jdbc.sql("SELECT id FROM reconciliation_run WHERE task_id=:task AND subscription_id=:subscription AND mode=:mode "+
                        "AND ((window_start IS NULL AND :start IS NULL) OR window_start=:start) "+
                        "AND ((window_end IS NULL AND :end IS NULL) OR window_end=:end) ORDER BY id DESC LIMIT 1")
                .param("task",taskId).param("subscription",subscriptionId).param("mode",mode.name()).param("start",start).param("end",end)
                .query(Long.class).list();
        return ids.isEmpty()?OptionalLong.empty():OptionalLong.of(ids.getFirst());
    }

    public void markRunning(long id) {
        jdbc.sql("UPDATE reconciliation_run SET status='RUNNING' WHERE id=:id AND status='QUEUED'").param("id", id).update();
    }

    public void finish(long id, String status, long sourceRows, long targetRows, long mismatchCount,
                       List<String> samples, String message) {
        jdbc.sql("UPDATE reconciliation_run SET status=:status,source_rows=:source,target_rows=:target,mismatch_count=:mismatch," +
                        "sample_keys_json=:samples,message=:message,ended_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status).param("source", sourceRows).param("target", targetRows)
                .param("mismatch", mismatchCount).param("samples", write(samples)).param("message", truncate(message, 3900)).update();
    }

    public long addDetail(long runId, long tableId, String status, long sourceRows, long targetRows,
                          long targetMissing, long targetRedundant, long fieldMismatch, long deleteResidue, String message) {
        long id = nextId();
        long mismatches = targetMissing + targetRedundant + fieldMismatch + deleteResidue;
        jdbc.sql("INSERT INTO reconciliation_detail(id,run_id,task_table_id,status,source_rows,target_rows,mismatch_count," +
                        "target_missing,target_redundant,field_mismatch,delete_residue,message,sample_keys_json) " +
                        "VALUES(:id,:run,:table,:status,:source,:target,:mismatch,:missing,:redundant,:field,:delete,:message,'[]')")
                .param("id", id).param("run", runId).param("table", tableId).param("status", status)
                .param("source", sourceRows).param("target", targetRows).param("mismatch", mismatches)
                .param("missing", targetMissing).param("redundant", targetRedundant).param("field", fieldMismatch)
                .param("delete", deleteResidue).param("message", truncate(message, 3900)).update();
        return id;
    }

    public void addField(long runId, long tableId, String sourceColumn, String targetColumn, String logicalType,
                         String metric, String sourceValue, String targetValue, boolean matched) {
        jdbc.sql("INSERT INTO reconciliation_field_result(id,run_id,task_table_id,source_column,target_column,logical_type," +
                        "metric,source_value,target_value,matched) VALUES(:id,:run,:table,:sourceColumn,:targetColumn,:type,:metric,:source,:target,:matched)")
                .param("id", nextId()).param("run", runId).param("table", tableId).param("sourceColumn", sourceColumn)
                .param("targetColumn", targetColumn).param("type", logicalType).param("metric", metric)
                .param("source", sourceValue).param("target", targetValue).param("matched", matched).update();
    }

    public void addDiscrepancy(long runId, long tableId, ReconciliationDiscrepancyType type,
                               Map<String, Object> key, String field, Object source, Object target) {
        jdbc.sql("INSERT INTO reconciliation_discrepancy(id,run_id,task_table_id,discrepancy_type,key_json,field_name,source_value,target_value) " +
                        "VALUES(:id,:run,:table,:type,:key,:field,:source,:target)")
                .param("id", nextId()).param("run", runId).param("table", tableId).param("type", type.name())
                .param("key", write(key)).param("field", field).param("source", source == null ? null : write(source))
                .param("target", target == null ? null : write(target)).update();
    }

    public List<ReconciliationRun> runs(long taskId) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE task_id=:task ORDER BY started_at DESC,id DESC LIMIT 100")
                .param("task", taskId).query(this::readRun).list();
    }
    public List<ReconciliationRun> runs(long taskId,long subscriptionId) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE task_id=:task AND subscription_id=:subscription ORDER BY started_at DESC,id DESC LIMIT 100")
                .param("task",taskId).param("subscription",subscriptionId).query(this::readRun).list();
    }

    public Map<String, Object> run(long taskId, long runId) {
        var run = jdbc.sql("SELECT * FROM reconciliation_run WHERE id=:id AND task_id=:task")
                .param("id", runId).param("task", taskId).query(this::readRun).optional()
                .orElseThrow(() -> new IllegalArgumentException("reconciliation run not found"));
        var details = jdbc.sql("SELECT * FROM reconciliation_detail WHERE run_id=:run ORDER BY id")
                .param("run", runId).query((rs, row) -> new ReconciliationDetail(rs.getLong("id"), runId,
                        rs.getLong("task_table_id"), rs.getString("status"), rs.getLong("source_rows"),
                        rs.getLong("target_rows"), rs.getLong("mismatch_count"), rs.getLong("target_missing"),
                        rs.getLong("target_redundant"), rs.getLong("field_mismatch"), rs.getLong("delete_residue"),
                        rs.getString("message"), fields(runId, rs.getLong("task_table_id")))).list();
        return Map.of("run", run, "details", details);
    }

    public PageResult<ReconciliationDiscrepancy> discrepancies(long taskId, long runId, int page, int size,
                                                                ReconciliationDiscrepancyType type) {
        run(taskId, runId);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 200));
        String where = "run_id=:run" + (type == null ? "" : " AND discrepancy_type=:type");
        var count = jdbc.sql("SELECT COUNT(*) FROM reconciliation_discrepancy WHERE " + where).param("run", runId);
        if (type != null) count.param("type", type.name());
        long total = count.query(Long.class).single();
        var query = jdbc.sql("SELECT * FROM reconciliation_discrepancy WHERE " + where + " ORDER BY id LIMIT :size OFFSET :offset")
                .param("run", runId).param("size", safeSize).param("offset", (long) safePage * safeSize);
        if (type != null) query.param("type", type.name());
        var items = query.query((rs, row) -> new ReconciliationDiscrepancy(rs.getLong("id"), runId,
                rs.getLong("task_table_id"), rs.getString("discrepancy_type"), readMap(rs.getString("key_json")),
                rs.getString("field_name"), readValue(rs.getString("source_value")), readValue(rs.getString("target_value")),
                rs.getObject("created_at", OffsetDateTime.class))).list();
        return new PageResult<>(items, total, safePage, safeSize, total == 0 ? 0 : (int) Math.ceil((double) total / safeSize));
    }

    public boolean hasActive(long taskId) {
        return jdbc.sql("SELECT COUNT(*) FROM reconciliation_run WHERE task_id=:task AND status IN ('QUEUED','RUNNING')")
                .param("task", taskId).query(Long.class).single() > 0;
    }

    public boolean hasAfterSnapshot(long taskId) {
        return jdbc.sql("SELECT COUNT(*) FROM reconciliation_run WHERE task_id=:task AND trigger_source='AFTER_SNAPSHOT'")
                .param("task", taskId).query(Long.class).single() > 0;
    }

    @Transactional
    public long createDeleteAudit(long taskId, long tableId, long mappingId, String fingerprint,
                                  Map<String, Object> key, OffsetDateTime eventAt) {
        return createDeleteAudit(taskId, tableId, mappingId, null, fingerprint, key, eventAt);
    }

    @Transactional
    public long createDeleteAudit(long taskId, long tableId, long mappingId, String eventId, String fingerprint,
                                  Map<String, Object> key, OffsetDateTime eventAt) {
        if (eventId != null) {
            var existing = jdbc.sql("SELECT id FROM cdc_delete_audit WHERE mapping_id=:mapping AND event_id=:event")
                    .param("mapping", mappingId).param("event", eventId).query(Long.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        jdbc.sql("UPDATE cdc_delete_audit SET status='SUPERSEDED',verified_at=CURRENT_TIMESTAMP " +
                        "WHERE mapping_id=:mapping AND key_fingerprint=:fingerprint AND status IN ('PENDING','DELIVERED','RESIDUE')")
                .param("mapping", mappingId).param("fingerprint", fingerprint).update();
        long id = nextId();
        jdbc.sql("INSERT INTO cdc_delete_audit(id,task_id,task_table_id,mapping_id,event_id,key_fingerprint,key_json,status,source_event_at) " +
                        "VALUES(:id,:task,:table,:mapping,:event,:fingerprint,:key,'PENDING',:eventAt)")
                .param("id", id).param("task", taskId).param("table", tableId).param("mapping", mappingId)
                .param("event", eventId).param("fingerprint", fingerprint).param("key", write(key)).param("eventAt", eventAt).update();
        return id;
    }

    public void markDeleteDelivered(long id) {
        jdbc.sql("UPDATE cdc_delete_audit SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=:id")
                .param("id", id).update();
    }

    public void markDeleteDelivered(long mappingId, String eventId) {
        jdbc.sql("UPDATE cdc_delete_audit SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,last_error=NULL " +
                        "WHERE mapping_id=:mapping AND event_id=:event")
                .param("mapping", mappingId).param("event", eventId).update();
    }

    public void markDeleteFailed(long id, String error) {
        jdbc.sql("UPDATE cdc_delete_audit SET status='FAILED',last_error=:error WHERE id=:id")
                .param("id", id).param("error", truncate(error, 1900)).update();
    }

    public void supersedeDelete(long mappingId, String fingerprint) {
        jdbc.sql("UPDATE cdc_delete_audit SET status='SUPERSEDED',verified_at=CURRENT_TIMESTAMP " +
                        "WHERE mapping_id=:mapping AND key_fingerprint=:fingerprint AND status IN ('PENDING','DELIVERED','RESIDUE')")
                .param("mapping", mappingId).param("fingerprint", fingerprint).update();
    }

    public List<DeleteAudit> pendingDeletes(long tableId, OffsetDateTime start, OffsetDateTime end,
                                            long afterId, int limit) {
        String window = start == null ? "" : " AND source_event_at>=:start AND source_event_at<:end";
        var query = jdbc.sql("SELECT * FROM cdc_delete_audit WHERE task_table_id=:table " +
                        "AND status IN ('DELIVERED','RESIDUE') AND id>:after" + window + " ORDER BY id LIMIT :limit")
                .param("table", tableId).param("after", afterId).param("limit", Math.max(1, Math.min(limit, 1000)));
        if (start != null) query.param("start", start).param("end", end);
        return query.query((rs, row) -> new DeleteAudit(rs.getLong("id"), readMap(rs.getString("key_json")))).list();
    }

    public void markDeleteVerified(long id, boolean residue) {
        jdbc.sql("UPDATE cdc_delete_audit SET status=:status,verified_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", residue ? "RESIDUE" : "VERIFIED").update();
    }

    public void cleanDeleteAudits(OffsetDateTime before) {
        jdbc.sql("DELETE FROM cdc_delete_audit WHERE status IN ('VERIFIED','SUPERSEDED') AND verified_at<:before")
                .param("before", before).update();
    }

    private List<ReconciliationFieldResult> fields(long runId, long tableId) {
        return jdbc.sql("SELECT * FROM reconciliation_field_result WHERE run_id=:run AND task_table_id=:table ORDER BY id")
                .param("run", runId).param("table", tableId).query((rs, row) -> new ReconciliationFieldResult(
                        rs.getLong("id"), runId, tableId, rs.getString("source_column"), rs.getString("target_column"),
                        rs.getString("logical_type"), rs.getString("metric"), rs.getString("source_value"),
                        rs.getString("target_value"), rs.getBoolean("matched"))).list();
    }

    private ReconciliationRun readRun(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReconciliationRun(rs.getLong("id"), rs.getLong("task_id"), rs.getString("status"),
                rs.getString("mode"), rs.getString("trigger_source"), rs.getObject("window_start", OffsetDateTime.class),
                rs.getObject("window_end", OffsetDateTime.class), rs.getLong("source_rows"), rs.getLong("target_rows"),
                rs.getLong("mismatch_count"), readList(rs.getString("sample_keys_json")), rs.getString("message"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class));
    }

    private long nextId() { return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single(); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid JSON", e); } }
    private List<String> readList(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
    private Map<String, Object> readMap(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (Exception e) { return Map.of(); } }
    private Object readValue(String value) { try { return value == null ? null : json.readValue(value, Object.class); } catch (Exception e) { return value; } }
    private String truncate(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    public record DeleteAudit(long id, Map<String, Object> key) {}
}
