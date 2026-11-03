package com.cdcplatform.repository;

import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.model.bi.BiDashboard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public BiDashboardRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------ dashboards

    /**
     * @param publishedOnly 非管理员只能看到已发布的看板，由调用方按角色决定
     */
    public List<BiDashboard> dashboards(String q, String status, boolean publishedOnly) {
        var sql = new StringBuilder("""
                SELECT d.*, (SELECT COUNT(*) FROM bi_dashboard_card c WHERE c.dashboard_id = d.id) AS card_count
                  FROM bi_dashboard d WHERE 1=1""");
        if (q != null && !q.isBlank()) {
            sql.append(" AND LOWER(d.name) LIKE :like");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = :status");
        }
        if (publishedOnly) {
            sql.append(" AND d.status = 'PUBLISHED'");
        }
        sql.append(" ORDER BY d.updated_at DESC, d.id DESC");
        var spec = jdbc.sql(sql.toString());
        if (q != null && !q.isBlank()) {
            spec = spec.param("like", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status.trim().toUpperCase(Locale.ROOT));
        }
        return spec.query((rs, n) -> readDashboard(rs)).list();
    }

    public BiDashboard dashboard(long id) {
        return jdbc.sql("""
                        SELECT d.*, (SELECT COUNT(*) FROM bi_dashboard_card c WHERE c.dashboard_id = d.id) AS card_count
                          FROM bi_dashboard d WHERE d.id = :id""")
                .param("id", id)
                .query((rs, n) -> readDashboard(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("看板 " + id + " 不存在"));
    }

    public long create(String name, String description, String actor) {
        long id = nextId();
        jdbc.sql("""
                        INSERT INTO bi_dashboard (id, name, description, status, version, created_by)
                        VALUES (:id, :name, :description, 'DRAFT', 1, :actor)""")
                .param("id", id).param("name", name).param("description", description).param("actor", actor)
                .update();
        return id;
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

    private static BiDashboard readDashboard(ResultSet rs) throws SQLException {
        return new BiDashboard(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getInt("version"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getInt("card_count"));
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
