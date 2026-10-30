package com.cdcplatform.api;

import com.cdcplatform.model.dev.DevFlow;
import com.cdcplatform.model.dev.DevGraph;
import com.cdcplatform.repository.DevFlowRepository;
import com.cdcplatform.service.ConsoleIdentityService;
import com.cdcplatform.service.dev.DevFlowExecutor;
import com.cdcplatform.service.dev.DevGraphValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据开发（可视化数据流）的 REST 接口。 */
@RestController
@RequestMapping("/api")
public class DevFlowController {

    private final DevFlowRepository flows;
    private final DevFlowExecutor executor;
    private final DevGraphValidator validator;

    public DevFlowController(DevFlowRepository flows, DevFlowExecutor executor, DevGraphValidator validator) {
        this.flows = flows;
        this.executor = executor;
        this.validator = validator;
    }

    public record FlowInput(String name, String description, DevGraph graph) {
    }

    public record MetadataInput(String name, String description) {
    }

    public record PreviewInput(Integer limit) {
    }

    @GetMapping("/flows")
    Object flows(@RequestParam(defaultValue = "") String q,
                 @RequestParam(required = false) String status,
                 @RequestParam(defaultValue = "0") int page,
                 @RequestParam(defaultValue = "20") int size) {
        List<DevFlow> all = flows.flows(q, status);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 100));
        int from = (int) Math.min((long) safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (DevFlow flow : all.subList(from, to)) {
            items.add(summary(flow));
        }
        return Map.of("items", items, "total", all.size(), "page", safePage, "size", safeSize,
                "totalPages", all.isEmpty() ? 0 : (int) Math.ceil((double) all.size() / safeSize));
    }

    @GetMapping("/flows/summary")
    Object summary() {
        var summary = flows.summary();
        return Map.of("total", summary.total(), "running", summary.running(),
                "failed", summary.failed(), "succeeded", summary.succeeded());
    }

    @GetMapping("/flows/{id}")
    Object flow(@PathVariable long id) {
        DevFlow flow = flows.flow(id);
        DevGraph graph = flows.parseGraph(flow.graphJson());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", flow.id());
        body.put("name", flow.name());
        body.put("description", flow.description());
        body.put("status", flow.status());
        body.put("version", flow.version());
        body.put("graph", graph);
        body.put("nodeCount", graph.nodes().size());
        body.put("lastRunStatus", flow.lastRunStatus());
        body.put("lastRunAt", flow.lastRunAt());
        body.put("lastRunRows", flow.lastRunRows());
        body.put("createdBy", flow.createdBy());
        body.put("createdAt", flow.createdAt());
        body.put("updatedAt", flow.updatedAt());
        return body;
    }

    @PostMapping("/flows")
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestBody FlowInput input) {
        String name = requireName(input.name());
        long id = flows.create(name, input.description(), input.graph(), actor());
        return Map.of("id", id);
    }

    @PutMapping("/flows/{id}")
    void update(@PathVariable long id, @RequestBody FlowInput input) {
        flows.update(id, requireName(input.name()), input.description(), input.graph());
    }

    @PatchMapping("/flows/{id}/metadata")
    void metadata(@PathVariable long id, @RequestBody MetadataInput input) {
        flows.rename(id, requireName(input.name()), input.description());
    }

    @DeleteMapping("/flows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        flows.delete(id);
    }

    @PostMapping("/flows/validate")
    Object validate(@RequestBody FlowInput input) {
        var report = validator.validate(input.graph(), false);
        return Map.of("valid", report.valid(), "errors", report.errors(), "order", report.order());
    }

    @PostMapping("/flows/{id}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Object run(@PathVariable long id) {
        return Map.of("id", executor.submit(id, actor()));
    }

    @GetMapping("/flows/{id}/runs")
    Object runs(@PathVariable long id,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "20") int size) {
        return flows.runs(id, page, size);
    }

    @GetMapping("/flows/{id}/runs/{runId}")
    Object run(@PathVariable long id, @PathVariable long runId) {
        return Map.of("run", flows.run(id, runId), "nodes", flows.nodeRuns(runId));
    }

    @PostMapping("/flows/{id}/nodes/{nodeKey}/preview")
    Object preview(@PathVariable long id, @PathVariable String nodeKey,
                   @RequestBody(required = false) PreviewInput input) {
        int limit = input == null || input.limit() == null ? 200 : input.limit();
        return executor.preview(id, nodeKey, limit);
    }

    // ------------------------------------------------------------- internals

    private Map<String, Object> summary(DevFlow flow) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", flow.id());
        item.put("name", flow.name());
        item.put("description", flow.description());
        item.put("status", flow.status());
        item.put("version", flow.version());
        item.put("lastRunStatus", flow.lastRunStatus());
        item.put("lastRunAt", flow.lastRunAt());
        item.put("lastRunRows", flow.lastRunRows());
        item.put("updatedAt", flow.updatedAt());
        item.put("nodeCount", flows.parseGraph(flow.graphJson()).nodes().size());
        return item;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据流名称不能为空");
        }
        return name.trim();
    }

    private static String actor() {
        var request = currentRequest();
        if (request != null && request.getAttribute("audit-user") instanceof ConsoleIdentityService.User user) {
            return user.username();
        }
        return "system";
    }

    private static HttpServletRequest currentRequest() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }
}
