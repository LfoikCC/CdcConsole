package com.cdcplatform.service.bi;

import com.cdcplatform.config.BiProperties;
import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.bi.BiDashboard;
import com.cdcplatform.model.bi.BiDashboardFolder;
import com.cdcplatform.repository.BiDashboardRepository;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.bi.BiQueryBuilder.CardConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 看板的业务规则：可见性、布局校验、乐观锁、发布、批量取数。
 *
 * <p>校验一律放在「写库之前」——保存卡片是删光重插，先删后校验会让一次非法保存
 * 把用户已有的卡片全部抹掉。
 */
@Service
public class BiDashboardService {

    public record Detail(BiDashboard dashboard, List<BiCard> cards) {
    }

    public record SaveResult(int version, List<BiCard> cards) {
    }

    /** 单张卡片的取数结果：一张失败不影响整个看板。 */
    public record CardData(Long cardId, boolean ok, List<String> columns, List<List<Object>> rows,
                           boolean truncated, long elapsedMs, String error) {
    }

    public record DashboardData(long elapsedMs, List<CardData> cards) {
    }

    private static final Set<String> TYPES = Set.of(
            "bar", "hbar", "stack", "line", "area", "combo", "scatter", "pie", "funnel", "table", "kpi", "text");
    /** 数据表卡片最多能放多少列：够铺宽表，又不至于把查询和渲染拖垮。 */
    private static final int MAX_TABLE_FIELDS = 50;
    /** 首页「最近发布 / 最近查看」各显示几个：再多就该去目录里翻了。 */
    private static final int RECENT_LIMIT = 6;

    private final BiDashboardRepository dashboards;
    private final DatasetRepository datasets;
    private final BiQueryService queryService;
    private final BiProperties props;

    public BiDashboardService(BiDashboardRepository dashboards, DatasetRepository datasets,
                              BiQueryService queryService, BiProperties props) {
        this.dashboards = dashboards;
        this.datasets = datasets;
        this.queryService = queryService;
        this.props = props;
    }

    public List<BiDashboard> list(String q, String status, boolean admin) {
        // 未发布的看板只有管理员看得见，所以发布不是装饰性标记
        return dashboards.dashboards(q, status, !admin, null, false);
    }

    /**
     * 按文件夹列看板。
     *
     * @param folder null/空 = 全部；{@code root} = 只在根目录；数字 = 该文件夹及其所有子文件夹
     */
    public List<BiDashboard> list(String q, String status, boolean admin, String folder) {
        boolean rootOnly = "root".equalsIgnoreCase(folder == null ? "" : folder.trim());
        List<Long> ids = List.of();
        if (!rootOnly && folder != null && !folder.isBlank()) {
            ids = subtree(Long.parseLong(folder.trim()));
        }
        return dashboards.dashboards(q, status, !admin, ids, rootOnly);
    }

    /** 首页三块：最近发布、我最近看的、草稿。 */
    public record Overview(List<BiDashboard> published, List<BiDashboard> viewed, List<BiDashboard> drafts) {
    }

    public Overview overview(boolean admin, String username) {
        List<BiDashboard> published = dashboards.recentPublished(RECENT_LIMIT);
        List<BiDashboard> viewed = dashboards.recentViewed(username, !admin, RECENT_LIMIT);
        List<BiDashboard> drafts = admin
                ? dashboards.dashboards(null, "DRAFT", false, null, false).stream().limit(RECENT_LIMIT).toList()
                : List.of();
        return new Overview(published, viewed, drafts);
    }

    /** 记一次浏览：最近查看只跟「谁看的」有关，别人打开不会顶掉我的列表。 */
    public void recordView(long id, String username) {
        dashboards.dashboard(id);
        dashboards.recordView(id, username);
    }

    // ------------------------------------------------------------- folders

    public List<BiDashboardFolder> folders() {
        return dashboards.folders();
    }

    public long createFolder(String name, Long parentId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("请填写文件夹名称");
        if (parentId != null) dashboards.folder(parentId);
        return dashboards.createFolder(name.trim(), parentId);
    }

    public void renameFolder(long id, String name) {
        dashboards.folder(id);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("请填写文件夹名称");
        dashboards.renameFolder(id, name.trim());
    }

    /** 删文件夹不删看板：子文件夹和里面的看板都上移到父级。 */
    public void deleteFolder(long id) {
        dashboards.folder(id);
        dashboards.deleteFolder(id);
    }

    public void move(long id, Long folderId) {
        dashboards.dashboard(id);
        if (folderId != null) dashboards.folder(folderId);
        dashboards.moveToFolder(id, folderId);
    }

    /** 这个文件夹连同所有后代的 id：选中父目录时，里层子目录的看板也要出现。 */
    private List<Long> subtree(long root) {
        List<BiDashboardFolder> all = dashboards.folders();
        if (all.stream().noneMatch(folder -> folder.id() == root)) {
            throw new IllegalArgumentException("文件夹 " + root + " 不存在");
        }
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        for (BiDashboardFolder folder : all) {
            if (folder.parentId() != null) {
                children.computeIfAbsent(folder.parentId(), key -> new ArrayList<>()).add(folder.id());
            }
        }
        List<Long> ids = new ArrayList<>();
        List<Long> queue = new ArrayList<>(List.of(root));
        while (!queue.isEmpty()) {
            long id = queue.remove(0);
            ids.add(id);
            queue.addAll(children.getOrDefault(id, List.of()));
        }
        return ids;
    }

    public Detail detail(long id, boolean admin) {
        BiDashboard dashboard = dashboards.dashboard(id);
        requireVisible(dashboard, admin);
        return new Detail(dashboard, dashboards.cards(id));
    }

    public long create(String name, String description, String actor, Long folderId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写看板名称");
        }
        if (folderId != null) dashboards.folder(folderId);
        return dashboards.create(name.trim(), description == null ? "" : description.trim(), actor, folderId);
    }

    public void rename(long id, String name, String description) {
        BiDashboard dashboard = dashboards.dashboard(id);
        dashboards.updateMeta(id, name == null || name.isBlank() ? dashboard.name() : name.trim(),
                description == null ? dashboard.description() : description.trim());
    }

    public void delete(long id) {
        dashboards.dashboard(id);
        dashboards.delete(id);
    }

    public BiDashboard publish(long id, boolean published) {
        BiDashboard dashboard = dashboards.dashboard(id);
        List<BiCard> cards = dashboards.cards(id);
        if (published) {
            if (cards.isEmpty()) {
                throw new IllegalStateException("看板还没有卡片，先添加卡片再发布");
            }
            for (BiCard card : cards) {
                if (!"text".equals(card.type()) && card.datasetId() == null) {
                    throw new IllegalStateException("卡片「" + title(card) + "」还没有选择数据集");
                }
            }
        }
        dashboards.setStatus(id, published ? "PUBLISHED" : "DRAFT");
        return dashboards.dashboard(id);
    }

    /** 整看板覆盖保存：版本号对不上就拒绝，避免两个人互相覆盖。 */
    @Transactional
    public SaveResult saveCards(long id, int expectedVersion, List<BiCard> cards) {
        dashboards.dashboard(id);
        if (dashboards.touchVersion(id, expectedVersion) != 1) {
            throw new IllegalStateException("看板已被其他人修改，请刷新后重试");
        }
        List<BiCard> safe = cards == null ? List.of() : cards;
        validateCards(safe);
        List<BiCard> saved = dashboards.replaceCards(id, safe);
        return new SaveResult(expectedVersion + 1, saved);
    }

    /**
     * 保存看板级筛选器：同一数据集的卡片取数时会自动带上这些条件。
     *
     * <p>单独一个入口而不是塞进卡片保存：筛选器改动不该让卡片的乐观锁版本 +1，
     * 否则两个人一个调布局、一个调筛选就会互相顶掉。
     */
    public void saveFilters(long id, List<Map<String, Object>> filters) {
        dashboards.dashboard(id);
        List<Map<String, Object>> safe = filters == null ? List.of() : filters;
        for (Map<String, Object> filter : safe) {
            if (filter.get("datasetId") == null || filter.get("column") == null) {
                throw new IllegalArgumentException("筛选条件缺少数据集或字段");
            }
            if (String.valueOf(filter.get("column")).isBlank()) {
                throw new IllegalArgumentException("筛选条件的字段不能为空");
            }
        }
        dashboards.updateFilters(id, safe);
    }

    /** 一次页面加载把所有卡片的数据取回来：串行执行，一张卡失败不影响其它卡。 */
    public DashboardData data(long id, boolean admin) {
        return data(id, admin, null);
    }

    /**
     * 带使用者当前选择的取数。
     *
     * <p>查看页上改筛选器的值不能写回看板定义（那是作者的东西），
     * 所以由前端把当前这份筛选器发过来，只影响这一次查询。
     *
     * @param runtimeFilters 为空表示用看板里存的那份
     */
    public DashboardData data(long id, boolean admin, List<Map<String, Object>> runtimeFilters) {
        long started = System.currentTimeMillis();
        Detail detail = detail(id, admin);
        List<Map<String, Object>> dashboardFilters = runtimeFilters != null
                ? runtimeFilters
                : (detail.dashboard().filters() == null ? List.of() : detail.dashboard().filters());
        List<CardData> results = new ArrayList<>();
        for (BiCard card : detail.cards()) {
            if ("text".equals(card.type()) || card.datasetId() == null) {
                results.add(new CardData(card.id(), true, List.of(), List.of(), false, 0, null));
                continue;
            }
            try {
                var result = queryService.query(card.datasetId(),
                        CardConfig.parse(withDashboardFilters(card, dashboardFilters)));
                results.add(new CardData(card.id(), true, result.columns(), result.rows(),
                        result.truncated(), result.elapsedMs(), null));
            } catch (RuntimeException e) {
                results.add(new CardData(card.id(), false, List.of(), List.of(), false, 0, message(e)));
            }
        }
        return new DashboardData(System.currentTimeMillis() - started, results);
    }

    /**
     * 把看板级筛选器并进这张卡的配置：只并命中这张卡的，卡片自己的条件排在前面。
     * 不直接改 card.config()，卡片对象还要原样回给前端。
     */
    private Map<String, Object> withDashboardFilters(BiCard card, List<Map<String, Object>> filters) {
        if (filters.isEmpty()) return card.config();
        List<Object> extra = new ArrayList<>(DashboardFilters.conditions(filters, card.datasetId(), card.id()));
        if (extra.isEmpty()) return card.config();
        Map<String, Object> config = new LinkedHashMap<>(card.config());
        List<Object> merged = new ArrayList<>();
        Object existing = config.get("filters");
        if (existing instanceof List<?> list) merged.addAll(list);
        merged.addAll(extra);
        config.put("filters", merged);
        return config;
    }

    /** 编辑器里显示数据集行数。 */
    public long count(long datasetId) {
        return queryService.count(datasetId);
    }

    /** 编辑器里选筛选值 / 排自定义顺序时，取一列的候选值。 */
    public BiQueryService.ValueList values(long datasetId, String column, int limit) {
        return queryService.values(datasetId, column, limit);
    }

    /** 编辑器里的实时预览：配置还没落库，直接用请求体里的配置取数。 */
    public BiQueryService.Result preview(long datasetId, Map<String, Object> config) {
        return queryService.query(datasetId, CardConfig.parse(config));
    }

    // ------------------------------------------------------------ internals

    private void requireVisible(BiDashboard dashboard, boolean admin) {
        if (!admin && !"PUBLISHED".equals(dashboard.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "看板尚未发布");
        }
    }

    private void validateCards(List<BiCard> cards) {
        if (cards.size() > props.getMaxCardsPerDashboard()) {
            throw new IllegalArgumentException("一个看板最多 " + props.getMaxCardsPerDashboard() + " 张卡片");
        }
        for (BiCard card : cards) {
            String type = card.type() == null ? "" : card.type().trim().toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("未知的卡片类型：" + card.type());
            }
            if (card.x() < 0 || card.y() < 0 || card.w() < 1 || card.w() > 12 || card.h() < 1 || card.h() > 40) {
                throw new IllegalArgumentException("卡片「" + title(card) + "」的布局超出范围");
            }
            if (card.x() + card.w() > 12) {
                throw new IllegalArgumentException("卡片「" + title(card) + "」宽度超出 12 列网格");
            }
            if ("text".equals(type)) {
                continue;
            }
            if (card.datasetId() == null) {
                throw new IllegalArgumentException("卡片「" + title(card) + "」还没有选择数据集");
            }
            datasets.dataset(card.datasetId());
            CardConfig config = CardConfig.parse(card.config());
            int dimensions = config.dimensions().size();
            int metrics = config.metrics().size();
            switch (type) {
                case "kpi" -> {
                    if (metrics != 1) {
                        throw new IllegalArgumentException("指标卡「" + title(card) + "」需要一个度量");
                    }
                }
                case "pie", "funnel" -> {
                    if (dimensions != 1 || metrics != 1) {
                        throw new IllegalArgumentException("图「" + title(card) + "」需要一个维度和一个度量");
                    }
                }
                case "bar", "hbar", "stack", "line", "area", "combo" -> {
                    if (dimensions != 1 || metrics < 1 || metrics > 4) {
                        throw new IllegalArgumentException("图「" + title(card) + "」需要一个维度和 1-4 个度量");
                    }
                }
                case "scatter" -> {
                    // 两个度量分别当 x / y，维度只用来给数据点打标签
                    if (dimensions != 1 || metrics != 2) {
                        throw new IllegalArgumentException("散点图「" + title(card) + "」需要一个维度和两个度量");
                    }
                }
                case "table" -> {
                    if (dimensions + metrics == 0) {
                        throw new IllegalArgumentException("表格「" + title(card) + "」至少要选一个字段");
                    }
                    // 宽表按「总字段数」限，维度多还是度量多由用户自己权衡
                    if (dimensions + metrics > MAX_TABLE_FIELDS) {
                        throw new IllegalArgumentException("表格「" + title(card) + "」最多 " + MAX_TABLE_FIELDS + " 个字段");
                    }
                }
                default -> {
                }
            }
        }
    }

    private static String title(BiCard card) {
        return card.title() == null || card.title().isBlank() ? card.type() : card.title();
    }

    private static String message(RuntimeException e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? "查询失败" : text;
    }
}
