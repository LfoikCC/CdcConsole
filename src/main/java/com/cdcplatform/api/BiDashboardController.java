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

    public record CreateInput(String name, String description, Long folderId) {
    }

    public record FolderInput(String name, Long parentId) {
    }

    public record MoveInput(Long folderId) {
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

    public record FiltersInput(List<Map<String, Object>> filters) {
    }

    private final BiDashboardService dashboards;

    public BiDashboardController(BiDashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @GetMapping("/dashboards")
    Object list(@RequestParam(defaultValue = "") String q,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String folder,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size,
                Authentication authentication) {
        List<Map<String, Object>> rows = dashboards.list(q, status, isAdmin(authentication), folder).stream()
                .map(this::view).toList();
        return PageResult.of(rows, page, size);
    }

    /** 首页三块：最近发布、我最近看的、草稿。 */
    @GetMapping("/dashboards/overview")
    Object overview(Authentication authentication) {
        String user = username(authentication);
        var body = dashboards.overview(isAdmin(authentication), user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", body.published().stream().map(this::view).toList());
        result.put("viewed", body.viewed().stream().map(this::view).toList());
        result.put("drafts", body.drafts().stream().map(this::view).toList());
        return result;
    }

    /**
     * @param track 只读页传 true：打开一次就记进「最近查看」。
     *              编辑器取详情不传，否则改一次样式也算浏览
     */
    @GetMapping("/dashboards/{id}")
    Object detail(@PathVariable long id, @RequestParam(defaultValue = "false") boolean track,
                  Authentication authentication) {
        var detail = dashboards.detail(id, isAdmin(authentication));
        // 先过可见性再记：否则只读用户能把看不见的草稿 id 塞进自己的最近查看
        if (track) dashboards.recordView(id, username(authentication));
        Map<String, Object> body = new LinkedHashMap<>(view(detail.dashboard()));
        body.put("cards", detail.cards());
        return body;
    }

    @PostMapping("/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestBody CreateInput input, Authentication authentication) {
        String actor = username(authentication);
        return Map.of("id", dashboards.create(input.name(), input.description(), actor, input.folderId()));
    }

    // ------------------------------------------------------------- folders

    @GetMapping("/dashboard-folders")
    Object folders() {
        return dashboards.folders();
    }

    @PostMapping("/dashboard-folders")
    @ResponseStatus(HttpStatus.CREATED)
    Object createFolder(@RequestBody FolderInput input) {
        return Map.of("id", dashboards.createFolder(input.name(), input.parentId()));
    }

    @PutMapping("/dashboard-folders/{id}")
    void renameFolder(@PathVariable long id, @RequestBody FolderInput input) {
        dashboards.renameFolder(id, input.name());
    }

    /** 删除文件夹不删看板：子文件夹与其中的看板都上移到父级。 */
    @DeleteMapping("/dashboard-folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFolder(@PathVariable long id) {
        dashboards.deleteFolder(id);
    }

    /** 把看板移动到指定文件夹；folderId 传 null 表示移回根目录。 */
    @PatchMapping("/dashboards/{id}/folder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void move(@PathVariable long id, @RequestBody MoveInput input) {
        dashboards.move(id, input.folderId());
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

    /** 看板级筛选器单独保存：不动卡片的版本号。 */
    @PutMapping("/dashboards/{id}/filters")
    void saveFilters(@PathVariable long id, @RequestBody FiltersInput input) {
        dashboards.saveFilters(id, input.filters());
    }

    @GetMapping("/dashboards/{id}/data")
    Object data(@PathVariable long id, Authentication authentication) {
        return dashboards.data(id, isAdmin(authentication));
    }

    /**
     * 查看页改筛选器的值后重新取数。
     *
     * <p>值只对这一次查询生效，不写回看板定义——那是作者的东西，
     * 而且改定义要走 /dashboards/{id}/filters，权限和乐观锁都不一样。
     */
    @PostMapping("/dashboards/{id}/data")
    Object dataWithFilters(@PathVariable long id, @RequestBody(required = false) FiltersInput input,
                           Authentication authentication) {
        return dashboards.data(id, isAdmin(authentication), input == null ? null : input.filters());
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
        row.put("folderId", dashboard.folderId());
        row.put("createdBy", dashboard.createdBy());
        row.put("createdAt", dashboard.createdAt());
        row.put("updatedAt", dashboard.updatedAt());
        row.put("publishedAt", dashboard.publishedAt());
        row.put("filters", dashboard.filters() == null ? List.of() : dashboard.filters());
        return row;
    }

    /**
     * 取当前用户名。
     *
     * <p>不能直接用 {@code authentication.getName()}：这里的 principal 是 User 记录，
     * 而 record 没实现 Principal，Spring 会退回 {@code principal.toString()}，
     * 拿到的是「User[id=1, username=admin, ...]」一整串（created_by 里已经有一批这样的脏数据）。
     */
    private static String username(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        if (principal instanceof com.cdcplatform.service.ConsoleIdentityService.User user) {
            return user.username();
        }
        return authentication.getName();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
