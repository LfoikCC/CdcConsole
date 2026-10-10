package com.cdcplatform.api;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.cdcplatform.model.PageResult;

@RestController
@RequestMapping("/api")
public class CdcController {
    private final ConfigRepository repository;
    private final ConnectionProbeService probe;
    private final EventDispatchService dispatch;
    private final SegmentCache cache;
    private final CdcProperties properties;
    private final SqlServerCdcPoller poller;
    private final RuntimeSettingsService settings;
    private final ColumnMappingService columnMappings;
    private final TaskRepository tasks;
    private final CaptureGroupService captureGroups;

    public CdcController(ConfigRepository repository, ConnectionProbeService probe, EventDispatchService dispatch,
                         SegmentCache cache, CdcProperties properties, SqlServerCdcPoller poller,
                         RuntimeSettingsService settings, ColumnMappingService columnMappings, TaskRepository tasks,
                         CaptureGroupService captureGroups) {
        this.repository = repository; this.probe = probe; this.dispatch = dispatch; this.cache = cache; this.properties = properties;
        this.poller = poller; this.settings = settings; this.columnMappings = columnMappings; this.tasks = tasks;
        this.captureGroups = captureGroups;
    }

    @GetMapping("/sources") Object sources(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) { return PageResult.of(repository.sources(), page, size); }
    @PostMapping("/sources") @ResponseStatus(HttpStatus.CREATED) Object source(@Valid @RequestBody SourceConfig value) {
        if (value.password() == null || value.password().isBlank()) throw new IllegalArgumentException("Password is required");
        return Map.of("id", repository.addSource(value));
    }
    @PutMapping("/sources/{id}") void source(@PathVariable("id") long id, @Valid @RequestBody SourceConfig value) { repository.updateSource(id, value); }
    @DeleteMapping("/sources/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteSource(@PathVariable("id") long id) { repository.deleteSource(id); }
    @PostMapping("/sources/{id}/probe") Object probe(@PathVariable("id") long id) { return probe.probe(repository.source(id, true)); }
    @GetMapping("/sources/{id}/databases") Object databases(@PathVariable("id") long id) { return probe.databases(repository.source(id, true)); }
    @GetMapping("/sources/{id}/tables") Object tables(@PathVariable("id") long id,
            @RequestParam(required=false) String database) {
        var source = repository.source(id, true);
        String selected = database == null || database.isBlank() ? source.databaseName() : database;
        return probe.tables(source, selected).stream().map(table -> {
            var result = new java.util.LinkedHashMap<>(table);
            result.put("database", selected);
            result.put("capability", source.dbType() == Types.DatabaseType.STARROCKS ? "POLLING_ONLY" : "STRICT_CDC");
            return result;
        }).toList();
    }

    @GetMapping("/targets") Object targets(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) { return PageResult.of(repository.targets(), page, size); }
    @PostMapping("/targets") @ResponseStatus(HttpStatus.CREATED) Object target(@Valid @RequestBody TargetConfig value) { return Map.of("id", repository.addTarget(value)); }
    @PutMapping("/targets/{id}") void target(@PathVariable("id") long id, @Valid @RequestBody TargetConfig value) { repository.updateTarget(id, value); }
    @GetMapping("/targets/{id}/tables") Object targetTables(@PathVariable("id") long id) { return probe.tables(repository.target(id, true)); }
    @GetMapping("/targets/{id}/topics") Object topics(@PathVariable("id") long id) { return probe.topics(repository.target(id, true)); }
    @PostMapping("/targets/{id}/probe") Object probeTarget(@PathVariable("id") long id) {
        var target = repository.target(id, true);
        if (target.targetType() == Types.TargetType.KAFKA)
            return Map.of("reachable", true, "detail", "Kafka 连接正常，发现 " + probe.topics(target).size() + " 个 Topic");
        return Map.of("reachable", true, "tables", probe.tables(target).size());
    }
    @DeleteMapping("/targets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteTarget(@PathVariable("id") long id) { repository.deleteTarget(id); }

    @GetMapping("/mappings") Object mappings(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        var singleTableMappings = tasks.tasks().stream().filter(task -> task.tables().size() == 1)
                .map(task -> repository.mapping(task.tables().getFirst().mappingId())).toList();
        return PageResult.of(singleTableMappings, page, size);
    }
    @PostMapping("/mappings") Object mapping(@RequestBody TableMapping value) { throw deprecatedMappingApi(); }
    @PutMapping("/mappings/{id}") void mapping(@PathVariable("id") long id, @RequestBody TableMapping value) { throw deprecatedMappingApi(); }
    @DeleteMapping("/mappings/{id}") void deleteMapping(@PathVariable("id") long id) { throw deprecatedMappingApi(); }

    @PostMapping("/events") @ResponseStatus(HttpStatus.ACCEPTED) Object event(@Valid @RequestBody ChangeEvent value) { return dispatch.accept(value); }
    @GetMapping("/status") Object status() { return Map.of(
            "sources", repository.sources().size(), "targets", repository.targets().size(), "mappings", repository.mappings().size(),
            "configuredTables", repository.mappings().size(), "enabledTables", repository.mappings().stream().filter(TableMapping::enabled).count(),
            "cacheBytes", cache.totalBytes(), "cacheSegments", cache.segments().size(),
            "segmentSizeMb", properties.getCache().getSegmentSizeMb(), "retentionDays", properties.getCache().getRetentionDays(),
            "polling", poller.statuses()); }
    @PostMapping("/mappings/{id}/reset-checkpoint") void reset(@PathVariable("id") long id) { throw deprecatedMappingApi(); }
    @PutMapping("/mappings/{id}/polling") Object polling(@PathVariable("id") long id, @RequestBody Map<String, Boolean> body) {
        throw deprecatedMappingApi();
    }
    @PutMapping("/mappings/polling") Object allPolling(@RequestBody Map<String, Boolean> body) {
        throw deprecatedMappingApi();
    }
    @GetMapping("/mappings/column-suggestions") Object columnSuggestions(
            @RequestParam long sourceId, @RequestParam long targetId,
            @RequestParam(defaultValue="") String sourceDatabase,
            @RequestParam(defaultValue="") String sourceSchema, @RequestParam String sourceTable,
            @RequestParam(defaultValue="") String targetSchema, @RequestParam String targetTable) {
        return columnMappings.suggest(sourceId, targetId, sourceDatabase, sourceSchema, sourceTable, targetSchema, targetTable);
    }
    @GetMapping("/settings") Object settings() { return settings.current(); }
    @PutMapping("/settings") Object settings(@Valid @RequestBody RuntimeSettingsService.Settings value) { return settings.update(value); }

    @GetMapping("/capture-groups") Object captureGroups() { return captureGroups.groups(); }
    @PostMapping("/capture-groups") @ResponseStatus(HttpStatus.CREATED) Object captureGroup(@RequestBody Map<String, Object> body) {
        return Map.of("id", captureGroups.createGroup(body));
    }
    @GetMapping("/capture-groups/{id}") Object captureGroup(@PathVariable long id) { return captureGroups.group(id); }
    @PostMapping("/capture-groups/{id}/actions/{action}") @ResponseStatus(HttpStatus.ACCEPTED) void captureGroupAction(@PathVariable long id, @PathVariable String action) { captureGroups.actionGroup(id, action); }
    @GetMapping("/capture-groups/{id}/subscriptions") Object subscriptions(@PathVariable long id) { return captureGroups.subscriptions(id); }
    @GetMapping("/capture-groups/{id}/subscription-options") Object subscriptionOptions(@PathVariable long id) { return captureGroups.subscriptionOptions(id); }
    @PostMapping("/capture-groups/{id}/subscriptions") @ResponseStatus(HttpStatus.CREATED) Object subscription(@PathVariable long id, @RequestBody Map<String, Object> body) {
        long subscriptionId = captureGroups.createSubscription(id, body);
        var created = captureGroups.subscription(subscriptionId);
        return Map.of("id", subscriptionId, "status", created.status());
    }
    @GetMapping("/capture-groups/{id}/batches") Object captureBatches(@PathVariable long id) { return captureGroups.captureBatches(id); }
    @GetMapping("/subscriptions/{id}/delivery-batches") Object subscriptionBatches(@PathVariable long id,
                                                                                      @RequestParam(required = false) String status,
                                                                                      @RequestParam(defaultValue = "0") int page,
                                                                                      @RequestParam(defaultValue = "50") int size) {
        var rows = captureGroups.subscriptionBatches(id).stream()
                .filter(row -> status == null || status.isBlank() || row.status().equalsIgnoreCase(status)).toList();
        return PageResult.of(rows, page, size);
    }
    @GetMapping("/subscriptions/{id}") Object subscription(@PathVariable long id) { return captureGroups.subscription(id); }
    @PutMapping("/subscriptions/{id}") void updateSubscription(@PathVariable long id, @RequestBody Map<String, Object> body) { captureGroups.updateSubscription(id, body); }
    @DeleteMapping("/subscriptions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteSubscription(@PathVariable long id) { captureGroups.actionSubscription(id, "cancel"); }
    @PostMapping("/subscriptions/{id}/actions/{action}") @ResponseStatus(HttpStatus.ACCEPTED) void subscriptionAction(@PathVariable long id, @PathVariable String action) { captureGroups.actionSubscription(id, action); }
    @PostMapping("/subscriptions/{id}/delivery-batches/{batchId}/retry") @ResponseStatus(HttpStatus.ACCEPTED) void retrySubscriptionBatch(@PathVariable long id, @PathVariable String batchId) { captureGroups.retrySubscriptionBatch(id, batchId); }
    private org.springframework.web.server.ResponseStatusException deprecatedMappingApi() {
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.GONE, "表映射已迁移，请使用 /api/tasks");
    }
}
