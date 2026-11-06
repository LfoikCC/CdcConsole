package com.cdcplatform.repository;

import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.bi.BiDashboard;
import com.cdcplatform.model.bi.BiDashboardFolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据看板的持久层。与 {@code DevFlowRepository} 同一套约定：
 * ID 取自 {@code cdc_config_id} 序列，JSON 列用 CLOB + Jackson，时间戳交给 SQL 侧。
 */
@Repository
public class BiDashboardRepository {

    /** 列表/详情都要带上卡片数，两处共用同一段 SELECT，避免字段漏改。 */
    private static final String SELECT_DASHBOARD = """
            SELECT d.*, (SELECT COUNT(*) FROM bi_dashboard_card c WHERE c.dashboard_id = d.id) AS card_count
              FROM bi_dashboard d""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public BiDashboardRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------ dashboards

    /**
     * @param publishedOnly 非管理员只能看到已发布的看板，由调用方按角色决定
     * @param folderIds     只看这些文件夹（调用方负责把子树 id 算进来）；null/空表示不按文件夹过滤
     * @param rootOnly      只看根目录（folder_id IS NULL）
     */
    public List<BiDashboard> dashboards(String q, String status, boolean publishedOnly,
                                        List<Long> folderIds, boolean rootOnly) {
        var sql = new StringBuilder(SELECT_DASHBOARD + " WHERE 1=1");
        if (q != null && !q.isBlank()) {
            sql.append(" AND LOWER(d.name) LIKE :like");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = :status");
        }
        if (publishedOnly) {
            sql.append(" AND d.status = 'PUBLISHED'");
        }
        if (rootOnly) {
            sql.append(" AND d.folder_id IS NULL");
        } else if (folderIds != null && !folderIds.isEmpty()) {
            // 显式占位符：JdbcClient 对集合参数的展开依赖版本，写死更稳
            sql.append(" AND d.folder_id IN (");
            for (int i = 0; i < folderIds.size(); i++) {
                sql.append(i > 0 ? ", :f" : ":f").append(i);
            }
            sql.append(')');
        }
        sql.append(" ORDER BY d.updated_at DESC, d.id DESC");
        var spec = jdbc.sql(sql.toString());
        if (q != null && !q.isBlank()) {
            spec = spec.param("like", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (!rootOnly && folderIds != null) {
            for (int i = 0; i < folderIds.size(); i++) {
                spec = spec.param("f" + i, folderIds.get(i));
            }
        }
        return spec.query((rs, n) -> readDashboard(rs)).list();
    }

    /** 最近发布：按发布时间倒序，只取前 N 个。 */
    public List<BiDashboard> recentPublished(int limit) {
        return jdbc.sql(SELECT_DASHBOARD + " WHERE d.status = 'PUBLISHED' AND d.published_at IS NOT NULL"
                        + " ORDER BY d.published_at DESC, d.id DESC LIMIT :n")
                .param("n", limit)
                .query((rs, n) -> readDashboard(rs)).list();
    }

    /** 最近查看：按「这个人」的浏览记录倒序，别人看过不影响我的列表。 */
    public List<BiDashboard> recentViewed(String username, boolean publishedOnly, int limit) {
        var sql = new StringBuilder(SELECT_DASHBOARD
                + " JOIN bi_dashboard_view v ON v.dashboard_id = d.id WHERE v.username = :user");
        if (publishedOnly) {
            sql.append(" AND d.status = 'PUBLISHED'");
        }
        sql.append(" ORDER BY v.viewed_at DESC LIMIT :n");
        return jdbc.sql(sql.toString())
                .param("user", username == null ? "" : username)
                .param("n", limit)
                .query((rs, n) -> readDashboard(rs)).list();
    }

    /** 记一次浏览：同一个人重复打开只更新时间。 */
    public void recordView(long id, String username) {
        if (username == null || username.isBlank()) return;
        jdbc.sql("MERGE INTO bi_dashboard_view (dashboard_id, username, viewed_at) "
                        + "KEY (dashboard_id, username) VALUES (:id, :user, CURRENT_TIMESTAMP)")
                .param("id", id).param("user", username).update();
    }

    public BiDashboard dashboard(long id) {
        return jdbc.sql("""
                        SELECT d.*, (SELECT COUNT(*) FROM bi_dashboard_card c WHERE c.dashboard_id = d.id) AS card_count
                          FROM bi_dashboard d WHERE d.id = :id""")
                .param("id", id)
                .query((rs, n) -> readDashboard(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("看板 " + id + " 不存在"));
    }

    public long create(String name, String description, String actor, Long folderId) {
        long id = nextId();
        jdbc.sql("""
                        INSERT INTO bi_dashboard (id, name, description, status, version, created_by, folder_id)
                        VALUES (:id, :name, :description, 'DRAFT', 1, :actor, :folderId)""")
                .param("id", id).param("name", name).param("description", description).param("actor", actor)
                .param("folderId", folderId)
                .update();
        return id;
    }

    /** 移动到文件夹；folderId 传 null 表示移回根目录。 */
    public void moveToFolder(long id, Long folderId) {
        jdbc.sql("UPDATE bi_dashboard SET folder_id = :folderId, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", id).param("folderId", folderId).update();
    }

    public void updateMeta(long id, String name, String description) {
        jdbc.sql("""
                        UPDATE bi_dashboard
                           SET name = COALESCE(:name, name),
                               description = COALESCE(:description, description),
                               version = version + 1,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = :id""")
                .param("id", id).param("name", name).param("description", description)
                .update();
    }

    /**
     * 只推进版本号，返回受影响行数。
     *
     * <p>0 行说明 version 对不上（别人先保存了）。乐观锁放在 SQL 的 WHERE 里而不是
     * 「先查再比再写」，否则两个并发保存都会读到同一个旧版本。
     */
    public int touchVersion(long id, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE bi_dashboard SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                         WHERE id = :id AND version = :version""")
                .param("id", id).param("version", expectedVersion)
                .update();
    }

    public void setStatus(long id, String status) {
        jdbc.sql("""
                        UPDATE bi_dashboard
                           SET status = :status,
                               published_at = CASE WHEN :status = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = :id""")
                .param("id", id).param("status", status)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM bi_dashboard WHERE id = :id").param("id", id).update();
    }

    /** 引用了某个数据集的看板数量：删数据集前要拦住，否则图表会静默变空。 */
    public long referencingDashboards(long datasetId) {
        return jdbc.sql("SELECT COUNT(DISTINCT dashboard_id) FROM bi_dashboard_card WHERE dataset_id = :id")
                .param("id", datasetId).query(Long.class).single();
    }

    // ------------------------------------------------------------- folders

    public List<BiDashboardFolder> folders() {
        return jdbc.sql("SELECT * FROM bi_dashboard_folder ORDER BY name, id")
                .query((rs, n) -> new BiDashboardFolder(rs.getLong("id"), rs.getString("name"),
                        (Long) rs.getObject("parent_id")))
                .list();
    }

    public BiDashboardFolder folder(long id) {
        return jdbc.sql("SELECT * FROM bi_dashboard_folder WHERE id = :id").param("id", id)
                .query((rs, n) -> new BiDashboardFolder(rs.getLong("id"), rs.getString("name"),
                        (Long) rs.getObject("parent_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("文件夹 " + id + " 不存在"));
    }

    public long createFolder(String name, Long parentId) {
        long id = nextId();
        jdbc.sql("INSERT INTO bi_dashboard_folder (id, name, parent_id) VALUES (:id, :name, :parentId)")
                .param("id", id).param("name", name).param("parentId", parentId).update();
        return id;
    }

    public void renameFolder(long id, String name) {
        jdbc.sql("UPDATE bi_dashboard_folder SET name = :name, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", id).param("name", name).update();
    }

    /**
     * 删除文件夹：子文件夹与其中的看板一并上移到父级，不删数据。
     *
     * <p>先读出 parentId 再改子级，否则 SQL 里自引用会读到已被改写的行。
     */
    public void deleteFolder(long id) {
        Long parentId = folder(id).parentId();
        jdbc.sql("UPDATE bi_dashboard_folder SET parent_id = :parentId WHERE parent_id = :id")
                .param("parentId", parentId).param("id", id).update();
        jdbc.sql("UPDATE bi_dashboard SET folder_id = :parentId WHERE folder_id = :id")
                .param("parentId", parentId).param("id", id).update();
        jdbc.sql("DELETE FROM bi_dashboard_folder WHERE id = :id").param("id", id).update();
    }

    // --------------------------------------------------------------- cards

    public List<BiCard> cards(long dashboardId) {
        return jdbc.sql("SELECT * FROM bi_dashboard_card WHERE dashboard_id = :id ORDER BY sort_order, id")
                .param("id", dashboardId)
                .query((rs, n) -> readCard(rs)).list();
    }

    /** 整看板覆盖保存：删光重插。调用方负责先校验、再调用，并自行开事务。 */
    public List<BiCard> replaceCards(long dashboardId, List<BiCard> cards) {
        jdbc.sql("DELETE FROM bi_dashboard_card WHERE dashboard_id = :id").param("id", dashboardId).update();
        List<BiCard> saved = new ArrayList<>();
        int order = 0;
        for (BiCard card : cards) {
            long id = nextId();
            jdbc.sql("""
                            INSERT INTO bi_dashboard_card
                                (id, dashboard_id, card_type, title, dataset_id, grid_x, grid_y, grid_w, grid_h,
                                 sort_order, config_json)
                            VALUES (:id, :dashboardId, :type, :title, :datasetId, :x, :y, :w, :h, :order, :config)""")
                    .param("id", id)
                    .param("dashboardId", dashboardId)
                    .param("type", card.type())
                    .param("title", card.title())
                    .param("datasetId", card.datasetId())
                    .param("x", card.x()).param("y", card.y()).param("w", card.w()).param("h", card.h())
                    .param("order", order++)
                    .param("config", writeConfig(card.config()))
                    .update();
            saved.add(new BiCard(id, card.type(), card.title(), card.datasetId(),
                    card.x(), card.y(), card.w(), card.h(), card.config()));
        }
        return saved;
    }

    // ------------------------------------------------------------ internals

    private BiDashboard readDashboard(ResultSet rs) throws SQLException {
        return new BiDashboard(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getInt("version"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getInt("card_count"),
                (Long) rs.getObject("folder_id"),
                parseFilters(rs.getString("filter_json")));
    }

    /** 看板级筛选器：存的就是一串 {datasetId, column, op, value}。 */
    private List<Map<String, Object>> parseFilters(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, new TypeReference<List<Map<String, Object>>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public void updateFilters(long id, List<Map<String, Object>> filters) {
        String raw;
        try {
            raw = json.writeValueAsString(filters == null ? List.of() : filters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("筛选条件序列化失败", e);
        }
        jdbc.sql("UPDATE bi_dashboard SET filter_json = :filters, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("filters", raw).param("id", id).update();
    }

    /**
     * 字段改名后同步卡片配置与看板筛选器里的引用。
     *
     * <p>卡片和筛选器存的是字段名，不同步的话已经做好的图表会直接报「找不到字段」。
     * 只替换 {@code column} / {@code name} 这类字段引用键，不动筛选值——
     * 值里出现同名字符串很常见，跟着改会把过滤条件改坏。
     */
    public void renameField(long datasetId, String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        var cards = jdbc.sql("SELECT id, config_json FROM bi_dashboard_card WHERE dataset_id = :id")
                .param("id", datasetId)
                .query((rs, row) -> rawRow(rs.getLong("id"), rs.getString("config_json")))
                .list();
        for (Map<String, Object> card : cards) {
            String raw = String.valueOf(card.get("raw"));
            if (raw.isBlank()) continue;
            try {
                JsonNode tree = json.readTree(raw);
                if (tree == null || !replaceRefs(tree, from, to)) continue;
                jdbc.sql("UPDATE bi_dashboard_card SET config_json = :config WHERE id = :id")
                        .param("config", json.writeValueAsString(tree))
                        .param("id", card.get("id")).update();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("卡片配置无法解析，字段改名中止：" + e.getOriginalMessage(), e);
            }
        }

        var dashboards = jdbc.sql("SELECT id, filter_json FROM bi_dashboard WHERE filter_json IS NOT NULL")
                .query((rs, row) -> rawRow(rs.getLong("id"), rs.getString("filter_json")))
                .list();
        for (Map<String, Object> dashboard : dashboards) {
            String raw = String.valueOf(dashboard.get("raw"));
            if (raw.isBlank()) continue;
            try {
                JsonNode tree = json.readTree(raw);
                if (tree == null || !renameFilterRefs(tree, datasetId, from, to)) continue;
                jdbc.sql("UPDATE bi_dashboard SET filter_json = :filters, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                        .param("filters", json.writeValueAsString(tree))
                        .param("id", dashboard.get("id")).update();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("看板筛选器无法解析，字段改名中止：" + e.getOriginalMessage(), e);
            }
        }
    }

    private static final List<String> FIELD_KEYS = List.of("column", "name", "fromColumn", "toColumn");

    private static Map<String, Object> rawRow(long id, String raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("raw", raw == null ? "" : raw);
        return row;
    }

    private static boolean replaceRefs(JsonNode node, String from, String to) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (String key : FIELD_KEYS) {
                JsonNode value = object.get(key);
                if (value != null && value.isTextual() && from.equals(value.asText())) {
                    object.put(key, to);
                    changed = true;
                }
            }
            // 计算字段的表达式用 {字段名} 引用原始列，改名时一起替换
            JsonNode expr = object.get("expr");
            if (expr != null && expr.isTextual() && expr.asText().contains("{" + from + "}")) {
                object.put("expr", expr.asText().replace("{" + from + "}", "{" + to + "}"));
                changed = true;
            }
            for (JsonNode child : object) {
                changed |= replaceRefs(child, from, to);
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                changed |= replaceRefs(child, from, to);
            }
        }
        return changed;
    }

    /** 筛选器按 datasetId 归属，只有作用在同一数据集上的字段引用才改。 */
    private static boolean renameFilterRefs(JsonNode node, long datasetId, String from, String to) {
        boolean changed = false;
        if (node instanceof ArrayNode array) {
            for (JsonNode filter : array) {
                if (!(filter instanceof ObjectNode object)) continue;
                JsonNode owner = object.get("datasetId");
                if (owner == null || owner.isNull() || owner.asLong() != datasetId) continue;
                JsonNode column = object.get("column");
                if (column != null && column.isTextual() && from.equals(column.asText())) {
                    object.put("column", to);
                    changed = true;
                }
                JsonNode conditions = object.get("conditions");
                if (conditions instanceof ArrayNode list) {
                    for (JsonNode condition : list) {
                        if (!(condition instanceof ObjectNode item)) continue;
                        JsonNode itemColumn = item.get("column");
                        if (itemColumn != null && itemColumn.isTextual() && from.equals(itemColumn.asText())) {
                            item.put("column", to);
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    private BiCard readCard(ResultSet rs) throws SQLException {
        return new BiCard(rs.getLong("id"), rs.getString("card_type"), rs.getString("title"),
                (Long) rs.getObject("dataset_id"),
                rs.getInt("grid_x"), rs.getInt("grid_y"), rs.getInt("grid_w"), rs.getInt("grid_h"),
                parseConfig(rs.getString("config_json")));
    }

    private Map<String, Object> parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("卡片配置无法解析：" + e.getOriginalMessage(), e);
        }
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return json.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("卡片配置无法序列化", e);
        }
    }

    private long nextId() {
        return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();
    }
}
