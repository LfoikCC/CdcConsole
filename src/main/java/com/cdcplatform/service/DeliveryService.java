package com.cdcplatform.service;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.DeliveryBatch;
import com.cdcplatform.model.DeliveryBatch.BatchStatus;
import com.cdcplatform.model.Types.AlertType;
import com.cdcplatform.model.Types.TaskStatus;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DeliveryBatchRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.ReconciliationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cdcplatform.service.delivery.DeliveryException;
import com.cdcplatform.service.delivery.TargetWriterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.HexFormat;

@Service
public class DeliveryService {
    private final SegmentCache cache;
    private final DeliveryBatchRepository batches;
    private final TargetWriterRegistry writers;
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final RuntimeEventService events;
    private final ReconciliationRepository reconciliations;
    private final ObjectMapper json;
    private final CaptureGroupService captureGroups;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final int maxAttempts;
    private final long maxPendingBytes;
    private final int retentionDays;
    private final Map<Long, Boolean> active = new ConcurrentHashMap<>();
    private final Set<Long> suspended = ConcurrentHashMap.newKeySet();

    public DeliveryService(SegmentCache cache, DeliveryBatchRepository batches, TargetWriterRegistry writers,
                           TaskRepository tasks, ConfigRepository configs, RuntimeEventService events,
                           CdcProperties properties, ReconciliationRepository reconciliations, ObjectMapper json,
                           CaptureGroupService captureGroups, org.springframework.transaction.PlatformTransactionManager transactions) {
        this.cache = cache; this.batches = batches; this.writers = writers; this.tasks = tasks;
        this.configs = configs; this.events = events; this.maxAttempts = properties.getDelivery().getMaxAttempts();
        this.maxPendingBytes = properties.getDelivery().getMaxPendingBytes();
        this.retentionDays = properties.getCache().getRetentionDays();
        this.reconciliations = reconciliations; this.json = json;
        this.captureGroups = captureGroups;
        this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    @PostConstruct void recover() {
        batches.recoverInterrupted();
        for (var descriptor : cache.readyBatches()) {
            if (descriptor.sourceStart()!=null && descriptor.sourceStart().startsWith("SUBSCRIPTION_RECOVERY")) continue;
            if (batches.exists(descriptor.batchId())) continue;
            Long taskId = tasks.taskIdForMapping(descriptor.mappingId());
            var table = tasks.tableForMapping(descriptor.mappingId());
            if (taskId == null || table == null) continue;
            var target = configs.target(configs.mapping(descriptor.mappingId()).targetId(), false);
            var batch = new DeliveryBatch(descriptor.batchId(), taskId, table.id(), descriptor.mappingId(),
                    descriptor.generation(), target.targetType(), descriptor.sourceStart(), descriptor.sourceEnd(),
                    descriptor.path().toString(), descriptor.checksum(), descriptor.eventCount(), descriptor.payloadBytes(),
                    BatchStatus.STAGED, 0, null, null, OffsetDateTime.now(), OffsetDateTime.now(), null);
            long currentGeneration = captureGroups.group(captureGroups.ensureForMapping(descriptor.mappingId())).generation();
            long captureGeneration = descriptor.captureGeneration() == null ? currentGeneration : descriptor.captureGeneration();
            if (captureGeneration != currentGeneration) continue; // Never resurrect a cancelled source generation.
            var recoveredEvents = cache.readBatch(descriptor.path(), descriptor.checksum());
            transaction.executeWithoutResult(status -> registerStage(batch, recoveredEvents, captureGeneration));
        }
        for (var task : tasks.tasks()) {
            boolean recoveredTable = task.tables().stream().anyMatch(table -> table.status() != null
                    && table.status() != com.cdcplatform.model.Types.TableStatus.FAILED
                    && configs.mapping(table.mappingId()).enabled());
            if (task.status() == TaskStatus.FAILED && recoveredTable)
                tasks.updateRuntimeStatus(task.id(), TaskStatus.DEGRADED);
        }
    }

    public synchronized StageResult stage(List<ChangeEvent> rawEvents, String sourceStart, String sourceEnd, long generation) {
        if (rawEvents == null || rawEvents.isEmpty()) throw new IllegalArgumentException("delivery batch is empty");
        if (batches.pendingBytes() >= maxPendingBytes)
            throw new IllegalStateException("本地待投递数据已达到磁盘背压上限");
        long mappingId = rawEvents.getFirst().mappingId();
        if (rawEvents.stream().anyMatch(event -> event.mappingId() != mappingId))
            throw new IllegalArgumentException("a delivery batch must contain one mapping");
        Long taskId = tasks.taskIdForMapping(mappingId);
        var table = tasks.tableForMapping(mappingId);
        if (taskId == null || table == null) throw new IllegalStateException("mapping is not attached to a sync task");
        var normalized = rawEvents.stream().map(ChangeEvent::normalized).toList();
        boolean targetOnly = sourceStart != null && sourceStart.startsWith("SUBSCRIPTION_");
        long captureGeneration = targetOnly ? generation : captureGroups.group(captureGroups.ensureForMapping(mappingId)).generation();
        var staged = cache.appendBatch(mappingId, generation, sourceStart, sourceEnd, normalized, captureGeneration);
        var target = configs.target(configs.mapping(mappingId).targetId(), false);
        var batch = new DeliveryBatch(staged.batchId(), taskId, table.id(), mappingId, generation, target.targetType(),
                sourceStart, sourceEnd, staged.path().toString(), staged.checksum(), staged.eventCount(), staged.payloadBytes(),
                BatchStatus.STAGED, 0, null, null, OffsetDateTime.now(), OffsetDateTime.now(), null);
        transaction.executeWithoutResult(status -> registerStage(batch, normalized, captureGeneration));
        // Do not let virtual delivery threads observe uncommitted fan-out or missing sequence metadata.
        if (!targetOnly) captureGroups.subscriptionsForMapping(mappingId).forEach(subscription -> {
            if (subscription.mappingId() != null) trigger(subscription.mappingId());
        });
        trigger(mappingId);
        return new StageResult(staged.batchId(), staged.path(), normalized);
    }

    private void registerStage(DeliveryBatch batch, List<ChangeEvent> normalized, long captureGeneration) {
        long mappingId = batch.mappingId(), generation = batch.generation();
        String sourceStart = batch.sourceStartPosition(), sourceEnd = batch.sourceEndPosition();
        batches.registerStaged(batch);
        boolean targetOnly = sourceStart != null && sourceStart.startsWith("SUBSCRIPTION_");
        if (!targetOnly) captureGroups.registerCaptureBatch(mappingId, sourceStart, sourceEnd, captureGeneration, batch.segmentPath(),
                batch.checksum(), batch.eventCount(), batch.payloadBytes());
        if (!targetOnly) batches.updateCaptureMetadata(batch.segmentPath(), normalized);
        // Compatibility fan-out: all mappings subscribed to the same source group reuse this
        // immutable segment. Their writers and delivery positions remain independent.
        for (var subscription : targetOnly ? List.<com.cdcplatform.model.TargetSubscription>of() : captureGroups.subscriptionsForMapping(mappingId)) {
            if (subscription.mappingId() == null || subscription.mappingId() == mappingId) continue;
            long otherMapping = subscription.mappingId();
            Long otherTaskId = tasks.taskIdForMapping(otherMapping);
            var otherTable = tasks.tableForMapping(otherMapping);
            if (otherTaskId == null || otherTable == null) continue;
            var otherTarget = configs.target(configs.mapping(otherMapping).targetId(), false);
            String derivedId = (batch.batchId() + "-m" + otherMapping);
            if (derivedId.length() > 96) derivedId = derivedId.substring(0, 96);
            var derived = new DeliveryBatch(derivedId, otherTaskId, otherTable.id(), otherMapping, generation,
                    otherTarget.targetType(), sourceStart, sourceEnd, batch.segmentPath(), batch.checksum(),
                    batch.eventCount(), batch.payloadBytes(), BatchStatus.STAGED, 0, null, null,
                    OffsetDateTime.now(), OffsetDateTime.now(), null);
            batches.registerStaged(derived);
            registerDeleteAudits(derived, normalized);
        }
        registerDeleteAudits(batch, normalized);
    }

    public DeliveryOutcome stageAndAwait(List<ChangeEvent> events, String sourceStart, String sourceEnd,
                                         long generation, Duration timeout) {
        var staged = stage(events, sourceStart, sourceEnd, generation);
        OffsetDateTime deadline = OffsetDateTime.now().plus(timeout);
        while (OffsetDateTime.now().isBefore(deadline)) {
            var batch = batches.batch(staged.batchId());
            if (batch.status() == BatchStatus.SUCCEEDED) return new DeliveryOutcome(batch.batchId(), true, null);
            if (batch.status() == BatchStatus.FAILED || batch.status() == BatchStatus.CANCELLED)
                return new DeliveryOutcome(batch.batchId(), false, batch.lastError());
            trigger(batch.mappingId());
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return new DeliveryOutcome(staged.batchId(), false, "等待目标批次完成超时");
    }

    @Scheduled(fixedDelay = 250)
    void tick() { batches.mappingsWithWork().forEach(this::trigger); }

    @Scheduled(cron = "0 30 2 * * *")
    synchronized void cleanSucceededFiles() {
        OffsetDateTime before = OffsetDateTime.now().minusDays(retentionDays);
        for (String file : batches.successfulFilesBefore(before))
            try { java.nio.file.Files.deleteIfExists(Path.of(file)); } catch (Exception ignored) {}
    }

    public void trigger(long mappingId) {
        if (!deliveryAllowed(mappingId)) return;
        if (active.putIfAbsent(mappingId, Boolean.TRUE) != null) return;
        Thread.startVirtualThread(() -> {
            try { drain(mappingId); }
            finally { active.remove(mappingId); }
        });
    }

    private void drain(long mappingId) {
        while (true) {
            if (!deliveryAllowed(mappingId)) return;
            DeliveryBatch head = batches.head(mappingId);
            if (head == null || head.status() == BatchStatus.FAILED || head.status() == BatchStatus.CANCELLED) return;
            if (head.status() == BatchStatus.RETRY_WAIT && head.nextRetryAt() != null
                    && head.nextRetryAt().isAfter(OffsetDateTime.now())) return;
            if (!batches.markDelivering(head.batchId())) continue;
            DeliveryBatch attempt = batches.batch(head.batchId());
            try {
                var batchEvents = cache.readBatch(Path.of(attempt.segmentPath()), attempt.checksum());
                writers.writer(mappingId).write(attempt, batchEvents);
                applyDeleteAudits(attempt, batchEvents);
                if (batches.complete(attempt.batchId())) {
                    captureGroups.markBatch(attempt.mappingId(), attempt.checksum(), "SUCCEEDED", null);
                    String deliveredPosition = attempt.sourceEndPosition() == null || attempt.sourceEndPosition().isBlank()
                            ? attempt.batchId() : attempt.sourceEndPosition();
                    captureGroups.markDelivered(attempt.mappingId(), deliveredPosition);
                    tasks.addLog(attempt.taskId(), attempt.taskTableId(), "INFO",
                            "批次 " + attempt.batchId() + " 写入成功，共 " + attempt.eventCount() + " 条");
                    events.publish("delivery-batch", Map.of("taskId", attempt.taskId(), "batchId", attempt.batchId(), "status", "SUCCEEDED"));
                }
                finalizeExtractionCycle(attempt.mappingId(), attempt.generation());
            } catch (Exception error) {
                String message = rootMessage(error);
                DeliveryBatch current = batches.batch(attempt.batchId());
                if (retryable(error) && current.attemptCount() < maxAttempts) {
                    long delay = retryDelayMillis(current.attemptCount());
                    batches.markRetry(current.batchId(), OffsetDateTime.now().plusNanos(delay * 1_000_000), message);
                    captureGroups.markBatch(current.mappingId(), current.checksum(), "RETRY_WAIT", message);
                    tasks.updateRuntimeStatus(current.taskId(), TaskStatus.DEGRADED);
                    events.publish("delivery-batch", Map.of("taskId", current.taskId(), "batchId", current.batchId(), "status", "RETRY_WAIT"));
                    return;
                }
                batches.markFailed(current.batchId(), message);
                captureGroups.markBatch(current.mappingId(), current.checksum(), "FAILED", message);
                if(tasks.taskIdForMapping(mappingId)==null)configs.setMappingEnabled(mappingId, false);
                writers.invalidate(mappingId);
                tasks.addLog(current.taskId(), current.taskTableId(), "ERROR",
                        "批次 " + current.batchId() + " 写入失败：" + message);
                tasks.createAlert(current.taskId(), current.taskTableId(), AlertType.TABLE_FAILURE,
                        "目标批次写入失败", "批次 " + current.batchId() + "：" + message);
                events.publish("delivery-batch", Map.of("taskId", current.taskId(), "batchId", current.batchId(), "status", "FAILED"));
                return;
            }
        }
    }

    private boolean deliveryAllowed(long mappingId) {
        if (suspended.contains(mappingId) || batches.recoveryActive(mappingId) || !tasks.captureActive(mappingId)) return false;
        return batches.subscriptionActive(mappingId);
    }

    public void retry(long taskId, String batchId) {
        var batch = batches.batch(batchId);
        if (batches.recoveryActive(batch.mappingId())) throw new IllegalStateException("该目标表正在恢复，请在恢复作业中重试");
        if (batch.taskId() != taskId) throw new IllegalArgumentException("delivery batch not found");
        batches.retry(batchId);
        suspended.remove(batch.mappingId());
        configs.setMappingEnabled(batch.mappingId(), true);
        writers.invalidate(batch.mappingId());
        // A previously failed task is otherwise excluded from the runtime coordinator after its batch is recovered.
        if (tasks.task(taskId).status() == TaskStatus.FAILED) tasks.updateRuntimeStatus(taskId, TaskStatus.DEGRADED);
        trigger(batch.mappingId());
    }

    public void cancel(long mappingId) {
        suspended.add(mappingId);
        batches.cancelPending(mappingId);
        writers.invalidate(mappingId);
    }

    public void suspend(long mappingId) { suspended.add(mappingId); }
    public void resume(long mappingId) { suspended.remove(mappingId); trigger(mappingId); }

    public long pendingEvents(long taskId) { return batches.pendingEvents(taskId); }
    public boolean hasPending(long mappingId) { return batches.hasPending(mappingId); }

    public void finalizeExtractionCycle(long mappingId, long generation) {
        if(tasks.taskIdForMapping(mappingId)!=null&&configs.source(configs.mapping(mappingId).sourceId(),false).dbType()==com.cdcplatform.model.Types.DatabaseType.STARROCKS)return;
        if (generation <= 0 || !batches.cycleReady(mappingId, generation)) return;
        String checkpoint = batches.cycleCheckpoint(mappingId, generation);
        configs.promoteSnapshotStage(mappingId, generation);
        if (checkpoint != null && !checkpoint.isBlank()) configs.saveCheckpoint(mappingId, checkpoint);
        batches.completeCycle(mappingId, generation);
    }

    /** Source snapshot baseline advances after durable capture, independently of target delivery. */
    @org.springframework.transaction.annotation.Transactional
    public void completeCapturedCycle(long mappingId,long generation,String checkpoint){
        configs.promoteSnapshotStage(mappingId,generation);
        configs.saveCheckpoint(mappingId,checkpoint);
        batches.completeCycle(mappingId,generation);
    }

    private boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DeliveryException delivery) return delivery.retryable();
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException
                    || current instanceof IOException || current instanceof HttpTimeoutException) return true;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ((state != null && (state.startsWith("08") || state.equals("40001")))
                        || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213) return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long retryDelayMillis(int attempt) {
        long base = Math.min(16_000L, 1_000L << Math.max(0, attempt - 1));
        return Math.max(250, (long) (base * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return Objects.toString(current.getMessage(), current.getClass().getSimpleName());
    }

    private void registerDeleteAudits(DeliveryBatch batch, List<ChangeEvent> events) {
        var table = tasks.tableForMapping(batch.mappingId());
        if (table == null || table.keyColumns().isEmpty()) return;
        ZoneId zone = ZoneId.of(tasks.task(batch.taskId()).timeZone());
        for (var event : events) {
            if (event.operation() != com.cdcplatform.model.Types.Operation.DELETE) continue;
            Map<String, Object> key = sourceKey(table, event.before());
            reconciliations.createDeleteAudit(batch.taskId(), batch.taskTableId(), batch.mappingId(),
                    event.id().toString(), fingerprint(key), key, OffsetDateTime.ofInstant(event.capturedAt(), zone));
        }
    }

    public void applyDeleteAudits(DeliveryBatch batch, List<ChangeEvent> events) {
        var table = tasks.tableForMapping(batch.mappingId());
        if (table == null || table.keyColumns().isEmpty()) return;
        for (var event : events) {
            if (event.operation() == com.cdcplatform.model.Types.Operation.DELETE) {
                reconciliations.markDeleteDelivered(batch.mappingId(), event.id().toString());
            } else {
                Map<String, Object> key = sourceKey(table, event.after());
                reconciliations.supersedeDelete(batch.mappingId(), fingerprint(key));
            }
        }
    }

    private Map<String, Object> sourceKey(com.cdcplatform.model.SyncTaskTable table, Map<String, Object> row) {
        var key = new LinkedHashMap<String, Object>();
        for (String column : table.keyColumns()) key.put(column, row.get(column));
        return key;
    }

    private String fingerprint(Map<String, Object> key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(key))); }
        catch (Exception e) { throw new IllegalStateException("无法生成删除主键指纹", e); }
    }

    public record StageResult(String batchId, Path path, List<ChangeEvent> events) {}
    public void suspendAndAwait(long mappingId) {
        suspended.add(mappingId);
        long deadline=System.currentTimeMillis()+120000;
        while(active.containsKey(mappingId)) {
            if(System.currentTimeMillis()>deadline) throw new IllegalStateException("等待当前目标批次结束超时，请稍后重试");
            try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("等待目标批次时被中断",e);}
        }
    }
    public record DeliveryOutcome(String batchId, boolean delivered, String error) {}
}
