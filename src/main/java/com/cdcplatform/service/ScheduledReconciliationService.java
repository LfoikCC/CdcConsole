package com.cdcplatform.service;

import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.model.ReconciliationRequest;
import com.cdcplatform.model.Types.ReconciliationTrigger;
import com.cdcplatform.model.Types.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ScheduledReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledReconciliationService.class);
    private final TaskRepository tasks;
    private final ReconciliationService reconciliation;
    private final Map<Long, ZonedDateTime> lastTriggered = new ConcurrentHashMap<>();
    private final java.util.Set<Long> running = ConcurrentHashMap.newKeySet();

    public ScheduledReconciliationService(TaskRepository tasks, ReconciliationService reconciliation) {
        this.tasks = tasks; this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "0 * * * * *")
    void triggerDueRuns() {
        for (var task : tasks.tasks()) {
            String expression = task.reconciliationCron();
            if (expression == null || expression.isBlank() || running.contains(task.id())
                    || !java.util.Set.of(TaskStatus.RUNNING, TaskStatus.DEGRADED).contains(task.status())
                    || task.tables().stream().noneMatch(table -> table.reconciliation().enabled())) continue;
            try {
                var now = ZonedDateTime.now(java.time.ZoneId.of(task.timeZone())).truncatedTo(ChronoUnit.MINUTES);
                var due = CronExpression.parse(expression).next(now.minusSeconds(1));
                if (due == null || !due.truncatedTo(ChronoUnit.MINUTES).equals(now)) continue;
                if (now.equals(lastTriggered.get(task.id()))) continue;
                lastTriggered.put(task.id(), now);
                running.add(task.id());
                Thread.startVirtualThread(() -> {
                    try { reconciliation.submit(task.id(), ReconciliationRequest.incremental(), ReconciliationTrigger.SCHEDULED); }
                    catch (Exception e) { log.warn("Scheduled reconciliation failed for task {}: {}", task.id(), e.getMessage()); }
                    finally { running.remove(task.id()); }
                });
            } catch (IllegalArgumentException e) {
                log.warn("Invalid reconciliation cron for task {}: {}", task.id(), expression);
            }
        }
    }
}
