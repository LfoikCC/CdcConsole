package com.cdcplatform.repository;

import com.cdcplatform.model.DeliveryBatch;
import com.cdcplatform.model.DeliveryBatch.BatchStatus;
import com.cdcplatform.model.DeliveryBatchPage;
import com.cdcplatform.model.Types.TargetType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class DeliveryBatchRepository {
    private final JdbcClient jdbc;

    public DeliveryBatchRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * 将一个刚写入磁盘的 CDC 批次注册为 "STAGED" 状态。
     *
     * 这里的设计意图是：先把 .ready 文件落盘，再把批次元信息写进 H2。这样在进程崩溃后，
     * 启动时可以从磁盘批次文件中扫描并恢复出未完成的投递任务，而不是丢失状态。
     *
     * 同时更新 sync_checkpoint 的 capture_position_json / position_json，表示该批次对应的源端
     * 采集位点已经被推进。注意：这里仍是按 task_table_id 维度更新单表 checkpoint，而不是按 batch 表维护。
     */
    @Transactional
    public boolean registerStaged(DeliveryBatch batch) {
        long exists = jdbc.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE batch_id=:id")
                .param("id", batch.batchId()).query(Long.class).single();
        if (exists > 0) return false;
        jdbc.sql("INSERT INTO cdc_delivery_batch(batch_id,task_id,task_table_id,mapping_id,generation,target_type," +
                        "source_start_position,source_end_position,segment_path,checksum,event_count,payload_bytes,status) " +
                        "VALUES(:id,:task,:table,:mapping,:generation,:target,:start,:end,:path,:checksum,:count,:bytes,'STAGED')")
                .param("id", batch.batchId()).param("task", batch.taskId()).param("table", batch.taskTableId())
                .param("mapping", batch.mappingId()).param("generation", batch.generation())
                .param("target", batch.targetType().name()).param("start", batch.sourceStartPosition())
                .param("end", batch.sourceEndPosition()).param("path", batch.segmentPath())
                .param("checksum", batch.checksum()).param("count", batch.eventCount())
                .param("bytes", batch.payloadBytes()).update();
        String position = batch.sourceEndPosition() == null || batch.sourceEndPosition().isBlank()
                ? batch.batchId() : batch.sourceEndPosition();
        int changed = jdbc.sql("UPDATE sync_checkpoint SET capture_position_json=:position,generation=:generation," +
                        "position_json=:position,updated_at=CURRENT_TIMESTAMP WHERE task_table_id=:table")
                .param("position", position).param("generation", batch.generation()).param("table", batch.taskTableId()).update();
        if (changed == 0) jdbc.sql("INSERT INTO sync_checkpoint(task_table_id,phase,position_json,capture_position_json,generation) " +
                        "VALUES(:table,'STREAMING',:position,:position,:generation)")
                .param("table", batch.taskTableId()).param("position", position)
                .param("generation", batch.generation()).update();
        return true;
    }

    /**
     * 进程重启或异常退出后，恢复之前还处于 DELIVERING 状态的批次。
     *
     * 这里不去扫描磁盘文件做恢复，而是把 H2 里已被标记为 DELIVERING 但未完成的记录重新置回 STAGED，
     * 让调度线程在下一轮 tick 中重新处理。磁盘上的 .ready 文件在 DeliveryService.recover() 中会被再次扫描并重建。
     */
    public void recoverInterrupted() {
        jdbc.sql("UPDATE cdc_delivery_batch SET status='STAGED',updated_at=CURRENT_TIMESTAMP " +
                "WHERE status='DELIVERING'").update();
    }

    /**
     * 返回所有仍存在投递工作/待处理状态的 mapping。
     *
     * 这里的工作对象是 mapping，而不是 task；因为一条数据流在逻辑上可按 mapping 维度做独立重试、调度和状态判断。
     */
    public List<Long> mappingsWithWork() {
        return jdbc.sql("SELECT DISTINCT mapping_id FROM cdc_delivery_batch WHERE status IN ('STAGED','DELIVERING','RETRY_WAIT','FAILED')")
                .query(Long.class).list();
    }

    /**
     * 取某个 mapping 当前最早的待投递批次。
     *
     * 这决定了投递队列的顺序：按 created_at + batch_id 取队头，保证同一个 mapping 的批次是串行、顺序投递的。
     * 这样可以避免乱序提交导致的位置/状态错位。
     */
    public DeliveryBatch head(long mappingId) {
        return jdbc.sql("SELECT * FROM cdc_delivery_batch WHERE mapping_id=:mapping " +
                        "AND status NOT IN ('SUCCEEDED','CANCELLED') ORDER BY created_at,batch_id LIMIT 1")
                .param("mapping", mappingId).query(this::map).optional().orElse(null);
    }

    /**
     * 根据 batch_id 读单条投递记录，用于重试、成功、失败、状态查询等场景。
     */
    public DeliveryBatch batch(String batchId) {
        return jdbc.sql("SELECT * FROM cdc_delivery_batch WHERE batch_id=:id").param("id", batchId)
                .query(this::map).optional().orElseThrow(() -> new IllegalArgumentException("delivery batch not found"));
    }

    public boolean exists(String batchId) {
        return jdbc.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE batch_id=:id").param("id", batchId)
                .query(Long.class).single() > 0;
    }

    /**
     * 任务线程开始真正投递某个 batch 时，先把状态改为 DELIVERING。
     *
     * 这一步是幂等性保护：只有 STAGED/RETRY_WAIT 的批次允许被消费；如果状态已变更则不会重复投递。
     */
    public boolean markDelivering(String batchId) {
        return jdbc.sql("UPDATE cdc_delivery_batch SET status='DELIVERING',attempt_count=attempt_count+1," +
                        "next_retry_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE batch_id=:id " +
                        "AND status IN ('STAGED','RETRY_WAIT')")
                .param("id", batchId).update() == 1;
    }

    /**
     * 将某个 batch 标记为等待重试，记录重试时间和错误摘要。
     */
    public void markRetry(String batchId, OffsetDateTime retryAt, String error) {
        var batch = batch(batchId);
        jdbc.sql("UPDATE cdc_delivery_batch SET status='RETRY_WAIT',next_retry_at=:retry,last_error=:error," +
                        "updated_at=CURRENT_TIMESTAMP WHERE batch_id=:id")
                .param("id", batchId).param("retry", retryAt).param("error", truncate(error)).update();
        jdbc.sql("UPDATE sync_task_table SET status='RETRYING',last_error=:error WHERE id=:table")
                .param("table", batch.taskTableId()).param("error", truncate(error)).update();
    }

    /**
     * 将该批次标记为失败，并同步更新表级状态。
     */
    public void markFailed(String batchId, String error) {
        var batch = batch(batchId);
        jdbc.sql("UPDATE cdc_delivery_batch SET status='FAILED',next_retry_at=NULL,last_error=:error," +
                        "updated_at=CURRENT_TIMESTAMP WHERE batch_id=:id")
                .param("id", batchId).param("error", truncate(error)).update();
        jdbc.sql("UPDATE sync_task_table SET status='FAILED',error_count=error_count+1,last_error=:error WHERE id=:table")
                .param("table", batch.taskTableId()).param("error", truncate(error)).update();
    }

    /**
     * 批次投递成功时更新状态：
     *  1) cdc_delivery_batch.status -> SUCCEEDED
     *  2) sync_checkpoint.delivery_position_json = source_end_position
     *  3) sync_task_table.processed_rows 累加
     *
     * 这里是“成批成功”的语义：一个批次全部写入目标后，才视为投递完成，并推进 delivery checkpoint。
     * 这也是为什么 recovery 时需要重扫 cdc_delivery_batch，而不仅依赖 source checkpoint。
     */
    @Transactional
    public boolean complete(String batchId) {
        var batch = batch(batchId);
        int changed = jdbc.sql("UPDATE cdc_delivery_batch SET status='SUCCEEDED',delivered_at=CURRENT_TIMESTAMP," +
                        "updated_at=CURRENT_TIMESTAMP,last_error=NULL,next_retry_at=NULL WHERE batch_id=:id AND status<>'SUCCEEDED'")
                .param("id", batchId).update();
        if (changed == 0) return false;
        String position = batch.sourceEndPosition() == null || batch.sourceEndPosition().isBlank()
                ? batch.batchId() : batch.sourceEndPosition();
        jdbc.sql("UPDATE sync_checkpoint SET delivery_position_json=:position,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE task_table_id=:table")
                .param("position", position).param("table", batch.taskTableId()).update();
        jdbc.sql("UPDATE sync_task_table SET processed_rows=processed_rows+:count,last_event_at=CURRENT_TIMESTAMP," +
                        "last_error=NULL,status='RUNNING' WHERE id=:table")
                .param("count", batch.eventCount()).param("table", batch.taskTableId()).update();
        return true;
    }

    /**
     * 对失败批次允许手动重试：只重置状态，不清理历史事件数据与 segment_path。
     */
    public void retry(String batchId) {
        jdbc.sql("UPDATE cdc_delivery_batch SET status='STAGED',attempt_count=0,next_retry_at=NULL,last_error=NULL," +
                        "updated_at=CURRENT_TIMESTAMP WHERE batch_id=:id AND status='FAILED'")
                .param("id", batchId).update();
    }

    /**
     * 对某个 mapping 的未完成批次做取消，通常用于用户手工暂停/停止。它不会删除磁盘文件，只改 H2 状态。
     */
    public void cancelPending(long mappingId) {
        jdbc.sql("UPDATE cdc_delivery_batch SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP " +
                        "WHERE mapping_id=:mapping AND status IN ('STAGED','DELIVERING','RETRY_WAIT','FAILED')")
                .param("mapping", mappingId).update();
    }

    /**
     * 当前还有多少投递待处理数据量，供背压/限流使用。
     */
    public long pendingBytes() {
        return jdbc.sql("SELECT COALESCE(SUM(payload_bytes),0) FROM cdc_delivery_batch " +
                        "WHERE status IN ('STAGED','DELIVERING','RETRY_WAIT','FAILED')")
                .query(Long.class).single();
    }

    /**
     * 清理已成功投递的磁盘 segment 文件。
     *
     * 注意：这里不是“成功即删”，而是先保留一段 retention 天数，达到阈值后才删除，防止回放/审计需求丢失。
     */
    public List<String> successfulFilesBefore(OffsetDateTime before) {
        return jdbc.sql("SELECT segment_path FROM cdc_delivery_batch WHERE status='SUCCEEDED' AND delivered_at<:before")
                .param("before", before).query(String.class).list();
    }

    /**
     * 任务层面待处理事件数，用于 UI 展示和监控。
     */
    public long pendingEvents(long taskId) {
        return jdbc.sql("SELECT COALESCE(SUM(event_count),0) FROM cdc_delivery_batch WHERE task_id=:task " +
                        "AND status IN ('STAGED','DELIVERING','RETRY_WAIT','FAILED')")
                .param("task", taskId).query(Long.class).single();
    }

    /**
     * 某个 mapping 是否还存在未完成投递。
     */
    public boolean hasPending(long mappingId) {
        return jdbc.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE mapping_id=:mapping " +
                        "AND status IN ('STAGED','DELIVERING','RETRY_WAIT')")
                .param("mapping", mappingId).query(Long.class).single() > 0;
    }

    /**
     * 分页查询某任务的投递批次，供前端任务详情页展示。status 可筛选 STAGED/SUCCEEDED/FAILED 等状态。
     */
    public DeliveryBatchPage page(long taskId, String status, int page, int size) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(200, size));
        String filter = status == null || status.isBlank() ? "" : " AND status=:status";
        var count = jdbc.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE task_id=:task" + filter).param("task", taskId);
        var query = jdbc.sql("SELECT * FROM cdc_delivery_batch WHERE task_id=:task" + filter +
                        " ORDER BY created_at DESC,batch_id DESC LIMIT :size OFFSET :offset")
                .param("task", taskId).param("size", safeSize).param("offset", (long) safePage * safeSize);
        if (!filter.isEmpty()) { count.param("status", status); query.param("status", status); }
        long total = count.query(Long.class).single();
        List<DeliveryBatch> items = query.query(this::map).list();
        return new DeliveryBatchPage(items, total, safePage, safeSize, (int) Math.ceil(total / (double) safeSize));
    }

    /**
     * 按 batch_id + part_no 记录某个目标写入分片的执行状态。
     * 这用于构建更细粒度的目标端写入观测，不是恢复的关键路径。
     */
    public void upsertPart(String batchId, int partNo, String label, String status, int attempts, String response) {
        int changed = jdbc.sql("UPDATE cdc_delivery_part SET label=:label,status=:status,attempt_count=:attempts," +
                        "response_body=:response,updated_at=CURRENT_TIMESTAMP WHERE batch_id=:batch AND part_no=:part")
                .param("batch", batchId).param("part", partNo).param("label", label).param("status", status)
                .param("attempts", attempts).param("response", response).update();
        if (changed == 0) jdbc.sql("INSERT INTO cdc_delivery_part(batch_id,part_no,label,status,attempt_count,response_body) " +
                        "VALUES(:batch,:part,:label,:status,:attempts,:response)")
                .param("batch", batchId).param("part", partNo).param("label", label).param("status", status)
                .param("attempts", attempts).param("response", response).update();
    }

    /**
     * 一个 extraction cycle 表示一次“捕获窗口”或“生成窗口”内的批次闭环。
     *
     * 这里的 generation 是同一个 mapping 内的批次轮次，它帮助把多个阶段（CAPTURING -> PREPARING -> READY -> SUCCEEDED）
     * 紧密地绑定到同一轮数据流上，保证 recovery 和 checkpoint 推进符合顺序。
     */
    public void beginExtractionCycle(long mappingId, long generation) {
        jdbc.sql("DELETE FROM cdc_extraction_cycle WHERE mapping_id=:mapping AND status='CAPTURING'")
                .param("mapping", mappingId).update();
        jdbc.sql("INSERT INTO cdc_extraction_cycle(mapping_id,generation,status) VALUES(:mapping,:generation,'CAPTURING')")
                .param("mapping", mappingId).param("generation", generation).update();
    }

    public ExtractionCycle activeCycle(long mappingId) {
        return jdbc.sql("SELECT * FROM cdc_extraction_cycle WHERE mapping_id=:mapping AND status<>'SUCCEEDED' " +
                        "ORDER BY generation DESC LIMIT 1")
                .param("mapping", mappingId).query((rs, row) -> new ExtractionCycle(rs.getLong("mapping_id"),
                        rs.getLong("generation"), rs.getString("status"), rs.getString("checkpoint_value")))
                .optional().orElse(null);
    }

    /**
     * 某轮 cycle 已经准备好 checkpoint，但还未确认该轮批次全部提交完成。
     */
    public void markCyclePrepared(long mappingId, long generation, String checkpoint) {
        jdbc.sql("UPDATE cdc_extraction_cycle SET status='PREPARING',checkpoint_value=:checkpoint " +
                        "WHERE mapping_id=:mapping AND generation=:generation")
                .param("mapping", mappingId).param("generation", generation).param("checkpoint", checkpoint).update();
    }

    /**
     * 某轮 cycle 中批次已全部就绪，等待最终成功确认后再推进 checkpoint。
     */
    public void markCycleReady(long mappingId, long generation, String checkpoint) {
        jdbc.sql("UPDATE cdc_extraction_cycle SET status='READY',checkpoint_value=:checkpoint " +
                        "WHERE mapping_id=:mapping AND generation=:generation")
                .param("mapping", mappingId).param("generation", generation).param("checkpoint", checkpoint).update();
    }

    /**
     * 当某轮 cycle 进入 READY 且本轮所有批次都已成功/取消时，才可以推进最终 checkpoint。
     */
    public boolean cycleReady(long mappingId, long generation) {
        long ready = jdbc.sql("SELECT COUNT(*) FROM cdc_extraction_cycle WHERE mapping_id=:mapping " +
                        "AND generation=:generation AND status='READY'")
                .param("mapping", mappingId).param("generation", generation).query(Long.class).single();
        long pending = jdbc.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE mapping_id=:mapping " +
                        "AND generation=:generation AND status<>'SUCCEEDED' AND status<>'CANCELLED'")
                .param("mapping", mappingId).param("generation", generation).query(Long.class).single();
        return ready > 0 && pending == 0;
    }

    /**
     * 取出当前轮次对应的 checkpoint 值，用于更新 cdc_checkpoint 或更高级的 recovery 状态。
     */
    public String cycleCheckpoint(long mappingId, long generation) {
        return jdbc.sql("SELECT checkpoint_value FROM cdc_extraction_cycle WHERE mapping_id=:mapping AND generation=:generation")
                .param("mapping", mappingId).param("generation", generation).query(String.class).optional().orElse(null);
    }

    /**
     * 表示某轮 extraction cycle 已经收口完成，不能再回退。
     */
    public void completeCycle(long mappingId, long generation) {
        jdbc.sql("UPDATE cdc_extraction_cycle SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP " +
                        "WHERE mapping_id=:mapping AND generation=:generation")
                .param("mapping", mappingId).param("generation", generation).update();
    }

    public record ExtractionCycle(long mappingId, long generation, String status, String checkpoint) {}

    /**
     * 将 ResultSet 映射成 DeliveryBatch，保留所有 batch 的状态、路径、位置和写入元信息。
     */
    private DeliveryBatch map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new DeliveryBatch(rs.getString("batch_id"), rs.getLong("task_id"), rs.getLong("task_table_id"),
                rs.getLong("mapping_id"), rs.getLong("generation"), TargetType.valueOf(rs.getString("target_type")),
                rs.getString("source_start_position"), rs.getString("source_end_position"), rs.getString("segment_path"),
                rs.getString("checksum"), rs.getInt("event_count"), rs.getLong("payload_bytes"),
                BatchStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                rs.getObject("next_retry_at", OffsetDateTime.class), rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("delivered_at", OffsetDateTime.class));
    }

    /**
     * 避免数据库字段溢出，截断过长错误信息，保证状态表不因异常文本过大而失败。
     */
    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 3900 ? value : value.substring(0, 3900);
    }
}
