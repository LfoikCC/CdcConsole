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

    public record FlowInput(String name, String description, DevGraph graph, Long folderId) {
    }

    public record MetadataInput(String name, String description) {
    }

    public record PreviewInput(Integer limit) {
    }

    public record FolderInput(String name, Long parentId) {
    }

    public record MoveInput(Long folderId) {
    }

    @GetMapping("/flows")
    Object flows(@RequestParam(defaultValue = "") String q,
                 @RequestParam(required = false) String status,
                 @RequestParam(defaultValue = "all") String view,
                 @RequestParam(required = false) String folder,
                 @RequestParam(defaultValue = "0") int page,
                 @RequestParam(defaultValue = "20") int size) {
        FolderScope scope = folderScope(folder);
        List<DevFlow> all = flows.flows(q, status, view, scope.ids(), scope.rootOnly());
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

    // ------------------------------------------------------------- folders

    @GetMapping("/flow-folders")
    Object folders() {
        return flows.folders();
    }

    @PostMapping("/flow-folders")
    @ResponseStatus(HttpStatus.CREATED)
    Object createFolder(@RequestBody FolderInput input) {
        return Map.of("id", flows.createFolder(requireName(input.name()), input.parentId()));
    }

    @PutMapping("/flow-folders/{id}")
    void renameFolder(@PathVariable long id, @RequestBody FolderInput input) {
        flows.renameFolder(id, requireName(input.name()));
    }

    /** 删除文件夹不会删数据流：子文件夹与其中的数据流会上移到父级。 */
    @DeleteMapping("/flow-folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFolder(@PathVariable long id) {
        flows.deleteFolder(id);
    }

    /** 把数据流移动到指定文件夹；folderId 传 null 表示移回根目录。 */
    @PatchMapping("/flows/{id}/folder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void moveFlow(@PathVariable long id, @RequestBody MoveInput input) {
        flows.moveFlow(id, input.folderId());
    }

    private record FolderScope(List<Long> ids, boolean rootOnly) {
    }

    /**
     * 解析 folder 参数。
     *
     * <p>不传 / 传 root 都表示根目录（「数据流」入口就是根目录）；传数字表示该文件夹及其子孙。
     */
    private FolderScope folderScope(String folder) {
        if (folder == null || folder.isBlank() || "root".equalsIgnoreCase(folder)) {
            return new FolderScope(null, true);
        }
        try {
            return new FolderScope(subtreeIds(Long.parseLong(folder.trim())), false);
        } catch (NumberFormatException e) {
            // 非法参数按「不过滤」处理，避免整页报错
            return new FolderScope(null, false);
        }
    }

    /** 选中某个文件夹时连同它的子孙一起显示——否则用户建了子目录却看不到里面的数据流。 */
    private List<Long> subtreeIds(long rootId) {
        List<com.cdcplatform.model.dev.DevFlowFolder> all = flows.folders();
        List<Long> ids = new ArrayList<>();
        ids.add(rootId);
        boolean grown = true;
        while (grown) {
            grown = false;
            for (var folder : all) {
                if (folder.parentId() != null && ids.contains(folder.parentId()) && !ids.contains(folder.id())) {
                    ids.add(folder.id());
                    grown = true;
                }
            }
        }
        return ids;
    }

    @GetMapping("/flows/summary")
    Object summary(@RequestParam(required = false) String folder) {
        FolderScope scope = folderScope(folder);
        var summary = flows.summary(scope.ids(), scope.rootOnly());
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
        body.put("folderId", flow.folderId());
        return body;
    }

    @PostMapping("/flows")
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestBody FlowInput input) {
        String name = requireName(input.name());
        long id = flows.create(name, input.description(), input.graph(), actor(), input.folderId());
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
        // 先发取消信号，再删记录：否则后台线程会继续写目标，run 记录被级联删掉后成了孤儿
        executor.cancelFlow(id);
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

    @PostMapping("/flows/{id}/runs/{runId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable long id, @PathVariable long runId) {
        executor.cancel(id, runId);
    }

    @GetMapping("/flows/{id}/runs")
    Object runs(@PathVariable long id,                @RequestParam(defaultValue = "0") int page,
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
        int limit = input == null || input.limit() == null ? 100 : input.limit();
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
        item.put("folderId", flow.folderId());
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
