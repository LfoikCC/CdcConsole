package com.cdcplatform.repository;

import com.cdcplatform.model.CaptureBatch;
import com.cdcplatform.model.CaptureGroup;
import com.cdcplatform.model.TargetSubscription;
import com.cdcplatform.model.SubscriptionBatch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class CaptureGroupRepository {
    private final JdbcClient jdbc;
    public CaptureGroupRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<CaptureGroup> groups() {
        return jdbc.sql("SELECT * FROM cdc_capture_group ORDER BY id").query(this::mapGroup).list();
    }
    public CaptureGroup group(long id) {
        return jdbc.sql("SELECT * FROM cdc_capture_group WHERE id=:id").param("id", id)
                .query(this::mapGroup).optional().orElseThrow(() -> new IllegalArgumentException("capture group not found"));
    }
    public CaptureGroup find(long sourceId, String database, String schema, String table) {
        return jdbc.sql("SELECT * FROM cdc_capture_group WHERE source_id=:source AND source_database IS NOT DISTINCT FROM :db " +
                        "AND source_schema IS NOT DISTINCT FROM :schema AND source_table=:table")
                .param("source", sourceId).param("db", database).param("schema", schema).param("table", table)
                .query(this::mapGroup).optional().orElse(null);
    }
    @Transactional
    public long createGroup(long sourceId, String database, String schema, String table, String syncMode,
                            String canonicalSchemaJson, long maxPendingBytes) {
        CaptureGroup existing = find(sourceId, database, schema, table);
        if (existing != null) return existing.id();
        long id = jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();
        jdbc.sql("INSERT INTO cdc_capture_group(id,source_id,source_database,source_schema,source_table,sync_mode,canonical_schema_json,max_pending_bytes) " +
                        "VALUES(:id,:source,:db,:schema,:table,:mode,:canonical,:max)")
                .param("id", id).param("source", sourceId).param("db", database).param("schema", schema).param("table", table)
                .param("mode", syncMode == null ? "FULL_INCREMENTAL" : syncMode)
                .param("canonical", canonicalSchemaJson == null ? "{}" : canonicalSchemaJson)
                .param("max", maxPendingBytes).update();
        return id;
    }
    public void updateStatus(long id, String status, String error) {
        jdbc.sql("UPDATE cdc_capture_group SET status=:status,last_error=:error,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status).param("error", error).update();
    }
    public void updateCapturePosition(long id, String position, long generation) {
        jdbc.sql("UPDATE cdc_capture_group SET capture_position_json=:position,generation=:generation,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("position", position).param("generation", generation).update();
    }

    public List<TargetSubscription> subscriptions(long groupId) {
        return jdbc.sql("SELECT * FROM cdc_target_subscription WHERE capture_group_id=:group ORDER BY id")
                .param("group", groupId).query(this::mapSubscription).list();
    }
    public TargetSubscription subscription(long id) {
        return jdbc.sql("SELECT * FROM cdc_target_subscription WHERE id=:id").param("id", id)
                .query(this::mapSubscription).optional().orElseThrow(() -> new IllegalArgumentException("subscription not found"));
    }
    public TargetSubscription subscriptionForMapping(long mappingId) {
        return jdbc.sql("SELECT * FROM cdc_target_subscription WHERE mapping_id=:mapping")
                .param("mapping", mappingId).query(this::mapSubscription).optional().orElse(null);
    }
    public void updateSubscriptionStatusForMapping(long mappingId, String status, String error) {
        jdbc.sql("UPDATE cdc_target_subscription SET status=:status,last_error=:error,updated_at=CURRENT_TIMESTAMP WHERE mapping_id=:mapping")
                .param("mapping", mappingId).param("status", status).param("error", error).update();
    }
    @Transactional
    public long createSubscription(long groupId, Long mappingId, long targetId, String targetSchema, String targetTable,
                                   String overrideJson, long maxPendingBytes) {
        return createSubscription(groupId, mappingId, targetId, targetSchema, targetTable, overrideJson, maxPendingBytes, "ACTIVE");
    }
    @Transactional
    public long createSubscription(long groupId, Long mappingId, long targetId, String targetSchema, String targetTable,
                                   String overrideJson, long maxPendingBytes, String status) {
        if (mappingId != null) {
            TargetSubscription existing = subscriptionForMapping(mappingId);
            if (existing != null) return existing.id();
        }
        long id = jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();
        jdbc.sql("INSERT INTO cdc_target_subscription(id,capture_group_id,mapping_id,target_id,target_schema,target_table,mapping_override_json,max_pending_bytes,status) " +
                        "VALUES(:id,:group,:mapping,:target,:schema,:table,:override,:max,:status)")
                .param("id", id).param("group", groupId).param("mapping", mappingId).param("target", targetId)
                .param("schema", targetSchema).param("table", targetTable)
                .param("override", overrideJson == null ? "{}" : overrideJson).param("max", maxPendingBytes).param("status", status == null ? "ACTIVE" : status).update();
        return id;
    }
    public void updateSubscriptionStatus(long id, String status, String error) {
        jdbc.sql("UPDATE cdc_target_subscription SET status=:status,last_error=:error,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("status", status).param("error", error).update();
    }
    public void updateSubscription(long id, Map<String, Object> body) {
        long targetId = body.get("targetId") == null ? -1 : Long.parseLong(String.valueOf(body.get("targetId")));
        String schema = body.get("targetSchema") == null ? null : String.valueOf(body.get("targetSchema"));
        String table = body.get("targetTable") == null ? null : String.valueOf(body.get("targetTable"));
        String override = body.get("mappingOverrideJson") == null ? "{}" : String.valueOf(body.get("mappingOverrideJson"));
        String sql = targetId < 0
                ? "UPDATE cdc_target_subscription SET target_schema=COALESCE(:schema,target_schema),target_table=COALESCE(:table,target_table),mapping_override_json=:override,mapping_version=mapping_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id"
                : "UPDATE cdc_target_subscription SET target_id=:target,target_schema=COALESCE(:schema,target_schema),target_table=COALESCE(:table,target_table),mapping_override_json=:override,mapping_version=mapping_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id";
        var query=jdbc.sql(sql).param("id", id).param("schema", schema).param("table", table).param("override", override);
        if (targetId >= 0) query.param("target", targetId);
        query.update();
    }
    public void updateDeliveryPosition(long id, String position) {
        jdbc.sql("UPDATE cdc_target_subscription SET delivery_position_json=:position,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("position", position).update();
    }

    @Transactional
    public boolean registerCaptureBatch(CaptureBatch batch) {
        long count = jdbc.sql("SELECT COUNT(*) FROM cdc_capture_batch WHERE capture_batch_id=:id").param("id", batch.captureBatchId()).query(Long.class).single();
        if (count > 0) return false;
        jdbc.sql("INSERT INTO cdc_capture_batch(capture_batch_id,capture_group_id,generation,source_start_position,source_end_position,segment_path,checksum,event_count,payload_bytes,status) " +
                        "VALUES(:id,:group,:generation,:start,:end,:path,:checksum,:events,:bytes,:status)")
                .param("id", batch.captureBatchId()).param("group", batch.captureGroupId()).param("generation", batch.generation())
                .param("start", batch.sourceStartPosition()).param("end", batch.sourceEndPosition()).param("path", batch.segmentPath())
                .param("checksum", batch.checksum()).param("events", batch.eventCount()).param("bytes", batch.payloadBytes()).param("status", batch.status()).update();
        return true;
    }
    @Transactional
    public int fanOut(CaptureBatch batch) {
        int created = 0;
        for (TargetSubscription subscription : subscriptions(batch.captureGroupId())) {
            String id = batch.captureBatchId() + "-s" + subscription.id();
            int changed = jdbc.sql("INSERT INTO cdc_subscription_batch(subscription_batch_id,subscription_id,capture_batch_id) " +
                            "SELECT :id,:subscription,:capture WHERE NOT EXISTS (SELECT 1 FROM cdc_subscription_batch WHERE subscription_id=:subscription AND capture_batch_id=:capture)")
                    .param("id", id).param("subscription", subscription.id()).param("capture", batch.captureBatchId()).update();
            created += changed;
            updateSubscriptionCounters(subscription.id());
        }
        return created;
    }
    public void fanOutToSubscription(CaptureBatch batch, long subscriptionId) {
        String id = batch.captureBatchId() + "-s" + subscriptionId;
        jdbc.sql("INSERT INTO cdc_subscription_batch(subscription_batch_id,subscription_id,capture_batch_id) " +
                        "SELECT :id,:subscription,:capture WHERE NOT EXISTS (SELECT 1 FROM cdc_subscription_batch WHERE subscription_id=:subscription AND capture_batch_id=:capture)")
                .param("id", id).param("subscription", subscriptionId).param("capture", batch.captureBatchId()).update();
        updateSubscriptionCounters(subscriptionId);
    }
    public List<CaptureBatch> captureBatches(long groupId) {
        return jdbc.sql("SELECT * FROM cdc_capture_batch WHERE capture_group_id=:group ORDER BY created_at,capture_batch_id")
                .param("group", groupId).query(this::mapCaptureBatch).list();
    }
    public List<SubscriptionBatch> subscriptionBatches(long subscriptionId) {
        return jdbc.sql("SELECT * FROM cdc_subscription_batch WHERE subscription_id=:subscription ORDER BY created_at,subscription_batch_id")
                .param("subscription", subscriptionId).query(this::mapSubscriptionBatch).list();
    }
    public void updateSubscriptionCounters(long id) {
        jdbc.sql("UPDATE cdc_target_subscription s SET pending_bytes=COALESCE((SELECT SUM(c.payload_bytes) FROM cdc_capture_batch c JOIN cdc_subscription_batch b ON b.capture_batch_id=c.capture_batch_id WHERE b.subscription_id=s.id AND b.status NOT IN ('SUCCEEDED','CANCELLED')),0), " +
                        "pending_events=COALESCE((SELECT SUM(c.event_count) FROM cdc_capture_batch c JOIN cdc_subscription_batch b ON b.capture_batch_id=c.capture_batch_id WHERE b.subscription_id=s.id AND b.status NOT IN ('SUCCEEDED','CANCELLED')),0) WHERE s.id=:id")
                .param("id", id).update();
    }
    public void markSubscriptionBatch(long mappingId, String checksum, String status, String error) {
        jdbc.sql("UPDATE cdc_subscription_batch b SET status=:status,last_error=:error,attempt_count=attempt_count+1,updated_at=CURRENT_TIMESTAMP,delivered_at=CASE WHEN :status='SUCCEEDED' THEN CURRENT_TIMESTAMP ELSE delivered_at END " +
                        "WHERE b.subscription_id=(SELECT id FROM cdc_target_subscription WHERE mapping_id=:mapping) AND b.capture_batch_id IN (SELECT capture_batch_id FROM cdc_capture_batch WHERE checksum=:checksum)")
                .param("mapping", mappingId).param("checksum", checksum).param("status", status).param("error", error).update();
        TargetSubscription subscription = subscriptionForMapping(mappingId);
        if (subscription != null) updateSubscriptionCounters(subscription.id());
    }
    public void retrySubscriptionBatch(long subscriptionId, String batchId) {
        jdbc.sql("UPDATE cdc_subscription_batch SET status='STAGED',attempt_count=0,next_retry_at=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE subscription_id=:subscription AND subscription_batch_id=:batch AND status IN ('FAILED','RETRY_WAIT')")
                .param("subscription", subscriptionId).param("batch", batchId).update();
        updateSubscriptionCounters(subscriptionId);
    }

    private CaptureGroup mapGroup(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new CaptureGroup(rs.getLong("id"),rs.getLong("source_id"),rs.getString("source_database"),rs.getString("source_schema"),rs.getString("source_table"),rs.getString("sync_mode"),rs.getString("canonical_schema_json"),rs.getString("status"),rs.getString("capture_position_json"),rs.getLong("generation"),rs.getLong("max_pending_bytes"),rs.getString("last_error"),rs.getObject("created_at", OffsetDateTime.class),rs.getObject("updated_at", OffsetDateTime.class)); }
    private TargetSubscription mapSubscription(java.sql.ResultSet rs, int row) throws java.sql.SQLException { long mapping=rs.getLong("mapping_id"); return new TargetSubscription(rs.getLong("id"),rs.getLong("capture_group_id"),rs.wasNull()?null:mapping,rs.getLong("target_id"),rs.getString("target_schema"),rs.getString("target_table"),rs.getString("mapping_override_json"),rs.getLong("mapping_version"),rs.getString("status"),rs.getString("delivery_position_json"),rs.getLong("max_pending_bytes"),rs.getLong("pending_bytes"),rs.getLong("pending_events"),rs.getString("last_error"),rs.getObject("created_at", OffsetDateTime.class),rs.getObject("updated_at", OffsetDateTime.class)); }
    private CaptureBatch mapCaptureBatch(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new CaptureBatch(rs.getString("capture_batch_id"),rs.getLong("capture_group_id"),rs.getLong("generation"),rs.getString("source_start_position"),rs.getString("source_end_position"),rs.getString("segment_path"),rs.getString("checksum"),rs.getInt("event_count"),rs.getLong("payload_bytes"),rs.getString("status"),rs.getObject("created_at", OffsetDateTime.class)); }
    private SubscriptionBatch mapSubscriptionBatch(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new SubscriptionBatch(rs.getString("subscription_batch_id"),rs.getLong("subscription_id"),rs.getString("capture_batch_id"),rs.getString("status"),rs.getInt("attempt_count"),rs.getObject("next_retry_at", OffsetDateTime.class),rs.getString("last_error"),rs.getObject("created_at", OffsetDateTime.class),rs.getObject("updated_at", OffsetDateTime.class),rs.getObject("delivered_at", OffsetDateTime.class)); }
}
