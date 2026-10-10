package com.cdcplatform.api;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.ReconciliationRepository;
import com.cdcplatform.repository.DeliveryBatchRepository;
import com.cdcplatform.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final TaskConfigurationService configuration;
    private final ReconciliationService reconciliation;
    private final ReconciliationRepository reconciliationRepository;
    private final RuntimeEventService events;
    private final ObjectMapper json;
    private final DeliveryBatchRepository deliveryBatches;
    private final DeliveryService delivery;
    private final TaskSubscriptionService targetSubscriptions;

    public TaskController(TaskRepository tasks, ConfigRepository configs, TaskConfigurationService configuration,
                          ReconciliationService reconciliation, ReconciliationRepository reconciliationRepository,
                          RuntimeEventService events, ObjectMapper json, DeliveryBatchRepository deliveryBatches,
                          DeliveryService delivery, TaskSubscriptionService targetSubscriptions) {
        this.tasks = tasks; this.configs = configs; this.configuration = configuration; this.reconciliation = reconciliation; this.events = events;
        this.reconciliationRepository = reconciliationRepository;
        this.json = json;
        this.deliveryBatches = deliveryBatches; this.delivery = delivery; this.targetSubscriptions = targetSubscriptions;
    }

    @GetMapping("/tasks")
    Object tasks(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) TaskStatus status,
                 @RequestParam(defaultValue = "all") String view,
                 @RequestParam(required = false) DatabaseType sourceType,
                 @RequestParam(required = false) TargetType targetType,
                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String query = q.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "running", "abnormal", "open-alerts").contains(view)) throw new IllegalArgumentException("任务视图无效");
        var alerted = "open-alerts".equals(view) ? tasks.alerts(AlertStatus.OPEN).stream().map(a -> a.taskId()).collect(java.util.stream.Collectors.toSet()) : Set.<Long>of();
        var filtered = tasks.tasks().stream().filter(task -> query.isEmpty() || task.name().toLowerCase(Locale.ROOT).contains(query))
                .filter(task -> switch(view) { case "running" -> task.status() == TaskStatus.RUNNING; case "abnormal" -> task.status() == TaskStatus.DEGRADED || task.status() == TaskStatus.FAILED; case "open-alerts" -> alerted.contains(task.id()); default -> true; })
                .filter(task -> status == null || task.status() == status)
                .filter(task -> sourceType == null || configs.source(task.sourceId(), false).dbType() == sourceType)
                .filter(task -> targetType == null || configs.target(task.targetId(), false).targetType() == targetType).toList();
        var result = PageResult.of(filtered, page, size);
        return new PageResult<>(result.items().stream().map(task -> {
            Map<String, Object> item = json.convertValue(task, new TypeReference<LinkedHashMap<String, Object>>() {});
            item.put("sourceType", configs.source(task.sourceId(), false).dbType());
            item.put("targetType", configs.target(task.targetId(), false).targetType());
            item.put("lastRunAt", tasks.latestRunAt(task.id()));
            item.put("lagMs", task.tables().stream().map(SyncTaskTable::lastLatencyMs).filter(Objects::nonNull)
                    .mapToLong(Long::longValue).max().orElse(0));
            item.put("errorSummary", task.tables().stream().map(SyncTaskTable::lastError)
                    .filter(error -> error != null && !error.isBlank()).findFirst().orElse(null));
            return item;
        }).toList(), result.total(), result.page(), result.size(), result.totalPages());
    }

    @GetMapping("/tasks/{id}") Object task(@PathVariable long id) {
        var task=tasks.task(id);
        Map<String,Object> result=json.convertValue(task,new TypeReference<LinkedHashMap<String,Object>>(){});
        result.put("targetSubscriptions",targetSubscriptions.list(id));
        return result;
    }

    @PostMapping("/tasks") @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody SyncTask task, @RequestParam(defaultValue = "false") boolean start) {
        long id = configuration.create(task);
        if (start) configuration.start(id, false);
        return Map.of("id", id);
    }

    @PutMapping("/tasks/{id}") void update(@PathVariable long id, @Valid @RequestBody SyncTask task) { configuration.update(id, task); }
    @PatchMapping("/tasks/{id}/metadata") void rename(@PathVariable long id, @RequestBody Map<String, String> body) {
        tasks.rename(id, body.get("name"), body.get("description"));
    }
    @DeleteMapping("/tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(@PathVariable long id) { tasks.delete(id); }
    @PostMapping("/tasks/validate") Object validate(@Valid @RequestBody SyncTask task) { return configuration.validate(task); }

    @PostMapping("/tasks/{id}/actions/start") void start(@PathVariable long id) { configuration.start(id, false); }
    @PostMapping("/tasks/{id}/actions/pause") void pause(@PathVariable long id) { configuration.pause(id); }
    @PostMapping("/tasks/{id}/actions/resume") void resume(@PathVariable long id) { configuration.start(id, true); }
    @PostMapping("/tasks/{id}/actions/stop") void stop(@PathVariable long id) { configuration.stop(id); }

    @GetMapping("/tasks/{id}/subscription-options") Object subscriptionOptions(@PathVariable long id) { return targetSubscriptions.options(id); }
    @GetMapping("/tasks/{id}/subscriptions") Object subscriptions(@PathVariable long id) { return targetSubscriptions.list(id); }
    @GetMapping("/tasks/{id}/subscriptions/{subscriptionId}") Object subscription(@PathVariable long id,@PathVariable long subscriptionId) { return targetSubscriptions.get(id,subscriptionId); }
    @GetMapping("/tasks/{id}/subscriptions/{subscriptionId}/reconciliation-options")
    Object subscriptionReconciliationOptions(@PathVariable long id,@PathVariable long subscriptionId) {
        return targetSubscriptions.reconciliationOptions(id,subscriptionId);
    }
    @PostMapping("/tasks/{id}/subscriptions") @ResponseStatus(HttpStatus.CREATED) Object createSubscription(@PathVariable long id,@RequestBody Map<String,Object> body) {
        long subscriptionId=targetSubscriptions.create(id,body);
        return Map.of("id",subscriptionId,"status",targetSubscriptions.get(id,subscriptionId).status());
    }
    @PutMapping("/tasks/{id}/subscriptions/{subscriptionId}") @ResponseStatus(HttpStatus.ACCEPTED)
    void updateSubscriptionMappings(@PathVariable long id,@PathVariable long subscriptionId,@RequestBody Map<String,Object> body) {
        targetSubscriptions.updateMappings(id,subscriptionId,body);
    }
    @PostMapping("/tasks/{id}/subscriptions/{subscriptionId}/actions/{action}") @ResponseStatus(HttpStatus.ACCEPTED)
    void subscriptionAction(@PathVariable long id,@PathVariable long subscriptionId,@PathVariable String action) { targetSubscriptions.action(id,subscriptionId,action); }
    @PutMapping("/tasks/{id}/subscriptions/{subscriptionId}/reconciliation") @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateSubscriptionReconciliation(@PathVariable long id,@PathVariable long subscriptionId,@RequestBody Map<String,Object> body) {
        targetSubscriptions.updateReconciliation(id,subscriptionId,body);
    }
    @GetMapping("/tasks/{id}/subscriptions/{subscriptionId}/reconciliations")
    Object subscriptionReconciliations(@PathVariable long id,@PathVariable long subscriptionId) {
        targetSubscriptions.get(id,subscriptionId);
        return reconciliationRepository.runs(id,subscriptionId);
    }
    @PostMapping("/tasks/{id}/subscriptions/{subscriptionId}/reconciliations") @ResponseStatus(HttpStatus.ACCEPTED)
    Object reconcileSubscription(@PathVariable long id,@PathVariable long subscriptionId,@RequestBody(required=false) ReconciliationRequest request) {
        long runId=reconciliation.submitForSubscription(id,subscriptionId,request==null?ReconciliationRequest.incremental():request,ReconciliationTrigger.MANUAL);
        return Map.of("id",runId);
    }

    @GetMapping("/tasks/{id}/instances") Object instances(@PathVariable long id) { return tasks.instances(id); }
    @GetMapping("/tasks/{id}/logs") Object logs(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return tasks.logs(id, page, size);
    }
    @GetMapping("/tasks/{id}/metrics") Object metrics(@PathVariable long id, @RequestParam(defaultValue = "24h") String range,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(defaultValue = "100") int size) {
        long hours = switch (range) { case "1h" -> 1; case "6h" -> 6; case "7d" -> 168; default -> 24; };
        var since = OffsetDateTime.now().minusHours(hours);
        return page == null ? tasks.metrics(id, since) : tasks.metrics(id, since, page, size);
    }
    @GetMapping("/tasks/{id}/reconciliations") Object reconciliations(@PathVariable long id) { return tasks.reconciliations(id); }
    @GetMapping("/tasks/{id}/delivery-batches") Object deliveryBatches(
            @PathVariable long id, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        tasks.task(id);
        return deliveryBatches.page(id, status, page, size);
    }
    @PostMapping("/tasks/{id}/delivery-batches/{batchId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    void retryDelivery(@PathVariable long id, @PathVariable String batchId) { delivery.retry(id, batchId); }
    @GetMapping("/tasks/{id}/reconciliations/{runId}") Object reconciliation(
            @PathVariable long id, @PathVariable long runId) { return reconciliationRun(id, runId); }
    @GetMapping("/tasks/{id}/reconciliations/{runId}/discrepancies") Object reconciliationDiscrepancies(
            @PathVariable long id, @PathVariable long runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) ReconciliationDiscrepancyType type) {
        return reconciliationDiscrepancyPage(id, runId, page, size, type);
    }
    @PostMapping("/tasks/{id}/reconciliations") @ResponseStatus(HttpStatus.ACCEPTED)
    Object reconcile(@PathVariable long id, @RequestBody(required = false) ReconciliationRequest request) {
        long runId = reconciliation.submit(id, request == null ? ReconciliationRequest.incremental() : request,
                ReconciliationTrigger.MANUAL);
        return Map.of("id", runId);
    }

    private Object reconciliationRun(long taskId, long runId) {
        return reconciliationRepository.run(taskId, runId);
    }
    private Object reconciliationDiscrepancyPage(long taskId, long runId, int page, int size,
                                                 ReconciliationDiscrepancyType type) {
        return reconciliationRepository.discrepancies(taskId, runId, page, size, type);
    }

    @GetMapping("/alerts") Object alerts(@RequestParam(required = false) AlertStatus status) { return tasks.alerts(status); }
    @PostMapping("/alerts/{id}/acknowledge") void acknowledge(@PathVariable long id) { tasks.updateAlert(id, AlertStatus.ACKNOWLEDGED); events.publish("alert", Map.of("alertId", id, "status", "ACKNOWLEDGED")); }
    @PostMapping("/alerts/{id}/resolve") void resolve(@PathVariable long id) { tasks.updateAlert(id, AlertStatus.RESOLVED); events.publish("alert", Map.of("alertId", id, "status", "RESOLVED")); }

    @GetMapping(value = "/runtime/events", produces = "text/event-stream") SseEmitter runtimeEvents(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) { return events.subscribe(lastEventId); }

    @GetMapping("/tasks/summary") Object summary() {
        var all = tasks.tasks();
        return Map.of("total", all.size(), "running", all.stream().filter(t -> t.status() == TaskStatus.RUNNING).count(),
                "degraded", all.stream().filter(t -> t.status() == TaskStatus.DEGRADED || t.status() == TaskStatus.FAILED).count(),
                "openAlerts", tasks.alerts(AlertStatus.OPEN).size());
    }
}
