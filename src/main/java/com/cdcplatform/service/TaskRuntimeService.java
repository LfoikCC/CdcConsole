package com.cdcplatform.service;

import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.ReconciliationRepository;
import com.cdcplatform.model.ReconciliationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskRuntimeService {
    private final TaskRepository tasks;
    private final SqlServerCdcPoller poller;
    private final ConfigRepository configs;
    private final TaskConfigurationService configuration;
    private final TaskRuntimeCoordinator coordinator;
    private final RuntimeEventService events;
    private final ReconciliationService reconciliation;
    private final ReconciliationRepository reconciliationRepository;
    private final DeliveryService delivery;
    private final int retentionDays;
    private final long sampleMs;
    private final Map<Long, Long> previousRows = new ConcurrentHashMap<>();
    private final Map<Long, Long> previousReads = new ConcurrentHashMap<>();
    private final Map<Long, Instant> previousSampleAt = new ConcurrentHashMap<>();

    @Autowired
    public TaskRuntimeService(TaskRepository tasks, SqlServerCdcPoller poller, ConfigRepository configs,
                              TaskConfigurationService configuration, TaskRuntimeCoordinator coordinator, RuntimeEventService events,
                              ReconciliationService reconciliation, ReconciliationRepository reconciliationRepository,
                              DeliveryService delivery,
                              @Value("${cdc.metrics.retention-days:7}") int retentionDays,
                              @Value("${cdc.metrics.sample-ms:60000}") long sampleMs) {
        this.tasks = tasks; this.poller = poller; this.configs = configs; this.configuration = configuration;
        this.coordinator = coordinator;
        this.events = events; this.reconciliation = reconciliation; this.reconciliationRepository = reconciliationRepository;
        this.delivery = delivery;
        this.retentionDays = retentionDays; this.sampleMs = sampleMs;
    }

    TaskRuntimeService(TaskRepository tasks, SqlServerCdcPoller poller, ConfigRepository configs,
                       TaskConfigurationService configuration, TaskRuntimeCoordinator coordinator, RuntimeEventService events,
                       ReconciliationService reconciliation, ReconciliationRepository reconciliationRepository,
                       int retentionDays, long sampleMs) {
        this(tasks, poller, configs, configuration, coordinator, events, reconciliation, reconciliationRepository,
                null, retentionDays, sampleMs);
    }

    @Scheduled(fixedDelay = 1000)
    void reconcileStatuses() {
        var statuses = new java.util.HashMap<>(poller.statuses());
        statuses.putAll(coordinator.statuses());
        for (var task : tasks.tasks()) {
            if (!Set.of(TaskStatus.STARTING, TaskStatus.SNAPSHOTTING, TaskStatus.RUNNING, TaskStatus.DEGRADED).contains(task.status())) {
                clearBaseline(task.id());
                continue;
            }
            int known = 0, healthy = 0, failed = 0;
            int retrying = 0;
            for (var table : task.tables()) {
                if (table.status() == TableStatus.RETRYING) { known++; retrying++; continue; }
                if (table.status() == TableStatus.FAILED && !configs.mapping(table.mappingId()).enabled()) {
                    known++; failed++; continue;
                }
                var status = statuses.get(table.mappingId());
                if (status == null) continue;
                known++;
                if (status.healthy()) {
                    healthy++;
                    tasks.updateTableStatus(table.id(), TableStatus.RUNNING, null);
                } else {
                    failed++;
                    tasks.updateTableStatus(table.id(), TableStatus.FAILED, status.error());
                    tasks.createAlert(task.id(), table.id(), AlertType.TABLE_FAILURE, "同步表运行异常", status.error());
                }
            }
            if (known == 0) continue;
            TaskStatus next = failed == task.tables().size() ? TaskStatus.FAILED
                    : failed > 0 || retrying > 0 ? TaskStatus.DEGRADED
                    : healthy == task.tables().size() ? TaskStatus.RUNNING : task.status();
            if (next != task.status()) {
                tasks.updateRuntimeStatus(task.id(), next);
                tasks.addLog(task.id(), null, next == TaskStatus.RUNNING ? "INFO" : "ERROR", "任务状态变更为 " + next);
                events.publish("task-status", Map.of("taskId", task.id(), "status", next.name()));
                if (next == TaskStatus.RUNNING && task.status() == TaskStatus.SNAPSHOTTING
                        && task.reconcileAfterSnapshot()
                        && task.tables().stream().anyMatch(table -> table.reconciliation().enabled())
                        && !reconciliationRepository.hasAfterSnapshot(task.id())) {
                    try {
                        reconciliation.submit(task.id(), new ReconciliationRequest(ReconciliationMode.FULL,
                                java.util.List.of(), null, null, true), ReconciliationTrigger.AFTER_SNAPSHOT);
                    } catch (Exception e) {
                        tasks.addLog(task.id(), null, "WARN", "全量同步后自动核对未启动：" + e.getMessage());
                    }
                }
            }
            long maxLag = task.tables().stream().filter(t -> t.lastLatencyMs() != null).mapToLong(t -> t.lastLatencyMs()).max().orElse(0);
            if (maxLag > task.alertLagSeconds() * 1000L)
                tasks.createAlert(task.id(), null, AlertType.DATA_DELAY, "同步延迟超过阈值", "当前最大延迟 " + maxLag + " ms");
        }
    }

    @Scheduled(fixedDelay = 60000)
    void detectStructureChanges() {
        for (var task : tasks.tasks()) {
            if (!Set.of(TaskStatus.RUNNING, TaskStatus.DEGRADED).contains(task.status())) continue;
            try {
                var report = configuration.validate(task);
                for (var table : task.tables()) {
                    String label = table.sourceDatabase() + "." + table.sourceTable();
                    boolean changed = report.tables().stream().anyMatch(result -> result.table().equals(label)
                            && result.errors().stream().anyMatch(error -> error.contains("源表结构已变化")));
                    if (!changed || table.status() == TableStatus.FAILED) continue;
                    configs.setMappingEnabled(table.mappingId(), false);
                    tasks.updateTableStatus(table.id(), TableStatus.FAILED, "源表结构已变化，请重新校验字段映射");
                    tasks.createAlert(task.id(), table.id(), AlertType.DDL_CHANGE, "源表结构发生变化", label + " 字段或主键发生变化，已暂停该表");
                    tasks.addLog(task.id(), table.id(), "ERROR", "源表结构发生变化，已暂停该表");
                    events.publish("task-status", Map.of("taskId", task.id(), "status", "DEGRADED"));
                }
            } catch (Exception e) {
                tasks.addLog(task.id(), null, "WARN", "表结构检查未完成：" + e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${cdc.metrics.sample-ms:60000}")
    synchronized void sampleMetrics() {
        Instant now = Instant.now();
        var activeTasks = tasks.samplingTasks();
        var activeIds = activeTasks.stream().map(t -> t.id()).collect(java.util.stream.Collectors.toSet());
        previousRows.keySet().removeIf(id -> !activeIds.contains(id));
        previousReads.keySet().removeIf(id -> !activeIds.contains(id));
        previousSampleAt.keySet().removeIf(id -> !activeIds.contains(id));
        for (var task : activeTasks) {
            long rows = task.tables().stream().mapToLong(t -> t.processedRows()).sum();
            long reads = configs.source(task.sourceId(), false).dbType() == DatabaseType.STARROCKS ? rows
                    : task.tables().stream().mapToLong(table -> coordinator.readCount(table.mappingId())).sum();
            long previous = previousRows.getOrDefault(task.id(), rows);
            long previousRead = previousReads.getOrDefault(task.id(), reads);
            double seconds = Math.max(0.001, Duration.between(previousSampleAt.getOrDefault(task.id(), now.minusMillis(sampleMs)), now).toMillis() / 1000d);
            previousRows.put(task.id(), rows);
            previousReads.put(task.id(), reads);
            previousSampleAt.put(task.id(), now);
            tasks.sampleMetrics(task.id(), delivery == null ? 0 : delivery.pendingEvents(task.id()), Math.max(0, reads - previousRead) / seconds,
                    Math.max(0, rows - previous) / seconds);
        }
        events.publish("metrics-updated", Map.of("at", now));
        tasks.cleanMetrics(OffsetDateTime.now().minusDays(retentionDays));
        reconciliationRepository.cleanDeleteAudits(OffsetDateTime.now().minusDays(30));
    }

    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void samplingReset(TaskRepository.SamplingReset event) { clearBaseline(event.taskId()); }

    private synchronized void clearBaseline(long id) {
        previousRows.remove(id);
        previousReads.remove(id);
        previousSampleAt.remove(id);
    }
}
