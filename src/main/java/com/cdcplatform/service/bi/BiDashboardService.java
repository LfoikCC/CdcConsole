package com.cdcplatform.service.bi;

import com.cdcplatform.config.BiProperties;
import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.bi.BiDashboard;
import com.cdcplatform.repository.BiDashboardRepository;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.bi.BiQueryBuilder.CardConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final Set<String> TYPES = Set.of("bar", "line", "pie", "table", "kpi", "text");

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
        return dashboards.dashboards(q, status, !admin);
    }

    public Detail detail(long id, boolean admin) {
        BiDashboard dashboard = dashboards.dashboard(id);
        requireVisible(dashboard, admin);
        return new Detail(dashboard, dashboards.cards(id));
    }

    public long create(String name, String description, String actor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写看板名称");
        }
        return dashboards.create(name.trim(), description == null ? "" : description.trim(), actor);
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

    /** 一次页面加载把所有卡片的数据取回来：串行执行，一张卡失败不影响其它卡。 */
    public DashboardData data(long id, boolean admin) {
        long started = System.currentTimeMillis();
        Detail detail = detail(id, admin);
        List<CardData> results = new ArrayList<>();
        for (BiCard card : detail.cards()) {
            if ("text".equals(card.type()) || card.datasetId() == null) {
                results.add(new CardData(card.id(), true, List.of(), List.of(), false, 0, null));
                continue;
            }
            try {
                var result = queryService.query(card.datasetId(), CardConfig.parse(card.config()));
                results.add(new CardData(card.id(), true, result.columns(), result.rows(),
                        result.truncated(), result.elapsedMs(), null));
            } catch (RuntimeException e) {
                results.add(new CardData(card.id(), false, List.of(), List.of(), false, 0, message(e)));
            }
        }
        return new DashboardData(System.currentTimeMillis() - started, results);
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
                case "pie" -> {
                    if (dimensions != 1 || metrics != 1) {
                        throw new IllegalArgumentException("饼图「" + title(card) + "」需要一个维度和一个度量");
                    }
                }
                case "bar", "line" -> {
                    if (dimensions != 1 || metrics < 1 || metrics > 4) {
                        throw new IllegalArgumentException("图「" + title(card) + "」需要一个维度和 1-4 个度量");
                    }
                }
                case "table" -> {
                    if (dimensions + metrics == 0) {
                        throw new IllegalArgumentException("表格「" + title(card) + "」至少要选一个字段");
                    }
                    if (dimensions > 8 || metrics > 8) {
                        throw new IllegalArgumentException("表格「" + title(card) + "」最多 8 个维度 + 8 个度量");
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
