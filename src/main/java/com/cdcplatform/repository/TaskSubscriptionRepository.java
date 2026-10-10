package com.cdcplatform.repository;

import com.cdcplatform.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Persistence boundary for the task-level target subscription model. */
@Repository
public class TaskSubscriptionRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public TaskSubscriptionRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public List<TaskTargetSubscription> subscriptions(long taskId) {
        return jdbc.sql("SELECT * FROM task_target_subscription WHERE task_id=:task ORDER BY id")
                .param("task", taskId).query((rs, row) -> mapSubscription(rs)).list().stream()
                .map(subscription -> withTables(subscription)).toList();
    }

    public TaskTargetSubscription subscription(long id) {
        var value = jdbc.sql("SELECT * FROM task_target_subscription WHERE id=:id").param("id", id)
                .query((rs, row) -> mapSubscription(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("target subscription not found"));
        return withTables(value);
    }

    public boolean hasSubscriptions(long taskId) {
        return jdbc.sql("SELECT COUNT(*) FROM task_target_subscription WHERE task_id=:task")
                .param("task", taskId).query(Long.class).single() > 0;
    }

    @Transactional
    public long createSubscription(long taskId, long targetId, String name, String description, String status) {
        long id = nextId();
        jdbc.sql("INSERT INTO task_target_subscription(id,task_id,target_id,name,description,status,initialization_status) " +
                        "VALUES(:id,:task,:target,:name,:description,:status,:status)")
                .param("id", id).param("task", taskId).param("target", targetId)
                .param("name", name).param("description", description == null ? "" : description)
                .param("status", status).update();
        return id;
    }

    @Transactional
    public long createRuntimeTable(long subscriptionId, long taskId, SyncTaskTable source, TableMapping mapping, ReconciliationConfig reconciliation) {
        long id = nextId();
        jdbc.sql("INSERT INTO sync_task_table(id,task_id,mapping_id,subscription_id,source_database,source_schema,source_table,target_schema,target_table," +
                        "columns_json,key_columns_json,include_operation,include_captured_at,operation_column,captured_at_column,captured_at_format," +
                        "operation_values_json,snapshot_profile,incremental_profile,structure_fingerprint,reconciliation_json,status) " +
                        "VALUES(:id,:task,:mapping,:subscription,:sd,:ss,:st,:ts,:tt,:columns,:keys,:operation,:captured,:operationColumn,:capturedColumn," +
                        ":capturedFormat,:operationValues,:snapshot,:incremental,:fingerprint,:reconciliation,'STARTING')")
                .param("id", id).param("task", taskId).param("mapping", mapping.id()).param("subscription", subscriptionId)
                .param("sd", source.sourceDatabase()).param("ss", blank(source.sourceSchema())).param("st", source.sourceTable())
                .param("ts", blank(mapping.targetSchema())).param("tt", mapping.targetTable())
                .param("columns", write(mapping.columns())).param("keys", write(source.keyColumns()))
                .param("operation", mapping.includeOperation()).param("captured", mapping.includeCapturedAt())
                .param("operationColumn", mapping.operationColumn()).param("capturedColumn", mapping.capturedAtColumn())
                .param("capturedFormat", mapping.capturedAtFormat()).param("operationValues", write(mapping.operationValues()))
                .param("snapshot", source.snapshotProfile().name()).param("incremental", source.incrementalProfile().name())
                .param("fingerprint", blank(source.structureFingerprint())).param("reconciliation", write(reconciliation)).update();
        return id;
    }

    @Transactional
    public long createTable(long subscriptionId, long sourceTableId, long runtimeTableId, long mappingId, Long groupId, String status) {
        long id = nextId();
        jdbc.sql("INSERT INTO target_subscription_table(id,subscription_id,source_task_table_id,runtime_task_table_id,capture_group_id,mapping_id,status,initialization_started_at) " +
                        "VALUES(:id,:subscription,:source,:runtime,:group,:mapping,:status,CURRENT_TIMESTAMP)")
                .param("id", id).param("subscription", subscriptionId).param("source", sourceTableId)
                .param("runtime", runtimeTableId).param("group", groupId).param("mapping", mappingId).param("status", status).update();
        return id;
    }

    public void updateTableState(long tableId, String status, String error, boolean done) {
        jdbc.sql("UPDATE target_subscription_table SET status=:status,initialization_error=:error,initialization_completed_at=" +
                        "CASE WHEN :done THEN CURRENT_TIMESTAMP ELSE initialization_completed_at END,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", tableId).param("status", status).param("error", error).param("done", done).update();
    }

    public void updateSubscriptionState(long id, String status, String error, boolean done) {
        jdbc.sql("UPDATE task_target_subscription SET status=:status,initialization_status=:status,last_error=:error,initialized_at=" +
                        "CASE WHEN :done THEN CURRENT_TIMESTAMP ELSE initialized_at END,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status).param("error", error).param("done", done).update();
    }

    public void updateRuntimeReconciliation(long runtimeTableId, ReconciliationConfig reconciliation) {
        jdbc.sql("UPDATE sync_task_table SET reconciliation_json=:reconciliation WHERE id=:id")
                .param("id", runtimeTableId).param("reconciliation", write(reconciliation)).update();
    }

    public void updateRuntimeStatus(long runtimeTableId, com.cdcplatform.model.Types.TableStatus status) {
        jdbc.sql("UPDATE sync_task_table SET status=:status WHERE id=:id")
                .param("id", runtimeTableId).param("status", status.name()).update();
    }

    public void updateRuntimeMapping(long runtimeTableId, TableMapping mapping, ReconciliationConfig reconciliation) {
        jdbc.sql("UPDATE sync_task_table SET mapping_id=:mapping,target_schema=:schema,target_table=:table,columns_json=:columns," +
                        "include_operation=:operation,include_captured_at=:captured,operation_column=:operationColumn," +
                        "captured_at_column=:capturedColumn,captured_at_format=:capturedFormat,operation_values_json=:operationValues," +
                        "reconciliation_json=:reconciliation,status='STARTING',processed_rows=0,error_count=0,last_error=NULL WHERE id=:id")
                .param("id",runtimeTableId).param("mapping",mapping.id()).param("schema",blank(mapping.targetSchema()))
                .param("table",mapping.targetTable()).param("columns",write(mapping.columns()))
                .param("operation",mapping.includeOperation()).param("captured",mapping.includeCapturedAt())
                .param("operationColumn",mapping.operationColumn()).param("capturedColumn",mapping.capturedAtColumn())
                .param("capturedFormat",mapping.capturedAtFormat()).param("operationValues",write(mapping.operationValues()))
                .param("reconciliation",write(reconciliation)).update();
    }

    public void restartTable(long tableId) {
        jdbc.sql("UPDATE target_subscription_table SET status='INITIALIZING_SNAPSHOT',initialization_error=NULL," +
                        "initialization_started_at=CURRENT_TIMESTAMP,initialization_completed_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id",tableId).update();
    }

    public List<Long> pendingInitializations(long taskId) {
        return jdbc.sql("SELECT id FROM task_target_subscription WHERE task_id=:task AND status IN ('INITIALIZING_SNAPSHOT','CATCHING_UP','FAILED','PAUSED')")
                .param("task", taskId).query(Long.class).list();
    }

    private TaskTargetSubscription withTables(TaskTargetSubscription subscription) {
        var tables = jdbc.sql("SELECT t.*, r.source_database,r.source_schema,r.source_table,r.target_schema,r.target_table,r.columns_json,r.key_columns_json," +
                        "r.include_operation,r.include_captured_at,r.operation_column,r.captured_at_column,r.captured_at_format,r.operation_values_json," +
                        "r.snapshot_profile,r.incremental_profile,r.structure_fingerprint,r.reconciliation_json,r.status AS runtime_status,r.processed_rows,r.error_count,r.last_latency_ms,r.last_event_at,r.last_error " +
                        "FROM target_subscription_table t JOIN sync_task_table r ON r.id=t.runtime_task_table_id WHERE t.subscription_id=:subscription ORDER BY t.id")
                .param("subscription", subscription.id()).query((rs, row) -> {
                    String subscriptionStatus=rs.getString("status");
                    SyncTaskTable mapping = new SyncTaskTable(rs.getLong("runtime_task_table_id"), rs.getLong("mapping_id"),
                            rs.getString("source_database"),rs.getString("source_schema"),rs.getString("source_table"),
                            rs.getString("target_schema"),rs.getString("target_table"),readMap(rs.getString("columns_json")),readList(rs.getString("key_columns_json")),
                            rs.getBoolean("include_operation"),rs.getBoolean("include_captured_at"),rs.getString("operation_column"),
                            rs.getString("captured_at_column"),rs.getString("captured_at_format"),readMap(rs.getString("operation_values_json")),
                            com.cdcplatform.model.Types.SnapshotProfile.valueOf(rs.getString("snapshot_profile")),
                            com.cdcplatform.model.Types.IncrementalProfile.valueOf(rs.getString("incremental_profile")),rs.getString("structure_fingerprint"),
                            readReconciliation(rs.getString("reconciliation_json")),
                            displayStatus(subscriptionStatus,rs.getString("runtime_status")),rs.getLong("processed_rows"),rs.getLong("error_count"),
                            nullableLong(rs,"last_latency_ms"),rs.getObject("last_event_at",OffsetDateTime.class),rs.getString("last_error"));
                    long group=rs.getLong("capture_group_id");
                    return new TaskTargetSubscriptionTable(rs.getLong("id"),rs.getLong("subscription_id"),rs.getLong("source_task_table_id"),
                            rs.getLong("runtime_task_table_id"),rs.wasNull()?null:group,rs.getLong("mapping_id"),rs.getString("status"),
                            rs.getString("initialization_error"),rs.getObject("initialization_started_at",OffsetDateTime.class),
                            rs.getObject("initialization_completed_at",OffsetDateTime.class),mapping);
                }).list();
        long events=tables.stream().mapToLong(t -> Math.max(0,t.mapping().processedRows())).sum();
        return new TaskTargetSubscription(subscription.id(),subscription.taskId(),subscription.targetId(),subscription.name(),subscription.description(),
                subscription.status(),subscription.initializationStatus(),subscription.lastError(),events,0,subscription.initializedAt(),
                subscription.createdAt(),subscription.updatedAt(),tables);
    }

    private TaskTargetSubscription mapSubscription(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskTargetSubscription(rs.getLong("id"),rs.getLong("task_id"),rs.getLong("target_id"),rs.getString("name"),
                rs.getString("description"),rs.getString("status"),rs.getString("initialization_status"),rs.getString("last_error"),0,0,
                rs.getObject("initialized_at",OffsetDateTime.class),rs.getObject("created_at",OffsetDateTime.class),
                rs.getObject("updated_at",OffsetDateTime.class),List.of());
    }
    private com.cdcplatform.model.Types.TableStatus displayStatus(String subscriptionStatus,String runtimeStatus){
        if("ACTIVE".equalsIgnoreCase(subscriptionStatus)) return com.cdcplatform.model.Types.TableStatus.RUNNING;
        if("FAILED".equalsIgnoreCase(subscriptionStatus)) return com.cdcplatform.model.Types.TableStatus.FAILED;
        if("PAUSED".equalsIgnoreCase(subscriptionStatus)) return com.cdcplatform.model.Types.TableStatus.PAUSED;
        return com.cdcplatform.model.Types.TableStatus.valueOf(runtimeStatus);
    }
    private long nextId(){return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();}
    private String blank(String value){return value==null||value.isBlank()?null:value;}
    private Long nullableLong(java.sql.ResultSet rs,String field) throws java.sql.SQLException { long value=rs.getLong(field); return rs.wasNull()?null:value; }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("invalid subscription JSON",e);}}
    private Map<String,String> readMap(String value){try{return value==null||value.isBlank()?Map.of():json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> readList(String value){try{return value==null||value.isBlank()?List.of():json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private ReconciliationConfig readReconciliation(String value){try{return value==null||value.isBlank()?ReconciliationConfig.disabled():json.readValue(value,ReconciliationConfig.class);}catch(Exception e){throw new IllegalStateException(e);}}
}
