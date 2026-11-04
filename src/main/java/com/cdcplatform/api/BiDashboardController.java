package com.cdcplatform.api;

import com.cdcplatform.model.PageResult;
import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.bi.BiDashboard;
import com.cdcplatform.service.bi.BiDashboardService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据分析：看板与卡片。
 *
 * <p>已保存卡片的取数走 GET——安全配置对所有非 GET 强制 ADMIN/OPERATOR，
 * 只读角色（VIEWER）发不出 POST，而只读看板页正是给 VIEWER 用的。
 * 编辑器里的实时预览是 POST，编辑器本身限管理员，不受影响。
 */
@RestController
@RequestMapping("/api/bi")
public class BiDashboardController {

    public record CreateInput(String name, String description) {
    }

    public record RenameInput(String name, String description) {
    }

    public record CardInput(Long id, String type, String title, Long datasetId,
                            Integer x, Integer y, Integer w, Integer h, Map<String, Object> config) {
        BiCard toCard() {
            return new BiCard(id, type == null ? "bar" : type.trim().toLowerCase(), title, datasetId,
                    x == null ? 0 : x, y == null ? 0 : y,
                    w == null ? 6 : w, h == null ? 8 : h,
                    config == null ? Map.of() : config);
        }
    }

    public record SaveCardsInput(Integer version, List<CardInput> cards) {
    }

    public record QueryInput(Long datasetId, Map<String, Object> config) {
    }

    private final BiDashboardService dashboards;

    public BiDashboardController(BiDashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/dashboards")
    Object list(@RequestParam(defaultValue = "") String q,
                @RequestParam(required = false) String status,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                Authentication authentication) {
        List<Map<String, Object>> rows = dashboards.list(q, status, isAdmin(authentication)).stream()
                .map(this::view).toList();
        return PageResult.of(rows, page, size);
    }

    @GetMapping("/dashboards/{id}")
    Object detail(@PathVariable long id, Authentication authentication) {
        var detail = dashboards.detail(id, isAdmin(authentication));
        Map<String, Object> body = new LinkedHashMap<>(view(detail.dashboard()));
        body.put("cards", detail.cards());
        return body;
    }

    @PostMapping("/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestBody CreateInput input, Authentication authentication) {
        String actor = authentication == null ? null : authentication.getName();
        return Map.of("id", dashboards.create(input.name(), input.description(), actor));
    }

    @PutMapping("/dashboards/{id}")
    void rename(@PathVariable long id, @RequestBody RenameInput input) {
        dashboards.rename(id, input.name(), input.description());
    }

    @DeleteMapping("/dashboards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        dashboards.delete(id);
    }

    @PostMapping("/dashboards/{id}/publish")
    Object publish(@PathVariable long id) {
        return view(dashboards.publish(id, true));
    }

    @PostMapping("/dashboards/{id}/unpublish")
    Object unpublish(@PathVariable long id) {
        return view(dashboards.publish(id, false));
    }

    @PutMapping("/dashboards/{id}/cards")
    Object saveCards(@PathVariable long id, @RequestBody SaveCardsInput input) {
        List<BiCard> cards = input.cards() == null ? List.of()
                : input.cards().stream().map(CardInput::toCard).toList();
        int version = input.version() == null ? 0 : input.version();
        var result = dashboards.saveCards(id, version, cards);
        return Map.of("version", result.version(), "cards", result.cards());
    }

    @GetMapping("/dashboards/{id}/data")
    Object data(@PathVariable long id, Authentication authentication) {
        return dashboards.data(id, isAdmin(authentication));
    }

    /** 数据集行数：库表数据集的元数据里没有，编辑器打开时现算一次。 */
    @GetMapping("/datasets/{id}/stats")
    Object stats(@PathVariable long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", dashboards.count(id));
        return body;
    }

    /** 筛选的候选值 / 自定义排序的候选值。GET 是为了让只读角色也能用。 */
    @GetMapping("/datasets/{id}/values")
    Object values(@PathVariable long id, @RequestParam String column,
                  @RequestParam(defaultValue = "200") int limit) {
        var result = dashboards.values(id, column, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("column", column);
        body.put("values", result.values());
        body.put("truncated", result.truncated());
        return body;
    }

    /** 编辑器里的实时预览：卡片可能还没保存，配置直接从请求体来。 */
    @PostMapping("/query")
    Object query(@RequestBody QueryInput input) {
        if (input.datasetId() == null) {
            throw new IllegalArgumentException("请先选择数据集");
        }
        return dashboards.preview(input.datasetId(), input.config());
    }

    // ------------------------------------------------------------ internals

    private Map<String, Object> view(BiDashboard dashboard) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", dashboard.id());
        row.put("name", dashboard.name());
        row.put("description", dashboard.description());
        row.put("status", dashboard.status());
        row.put("version", dashboard.version());
        row.put("cardCount", dashboard.cardCount());
        row.put("createdBy", dashboard.createdBy());
        row.put("createdAt", dashboard.createdAt());
        row.put("updatedAt", dashboard.updatedAt());
        row.put("publishedAt", dashboard.publishedAt());
        return row;
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
