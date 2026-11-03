package com.cdcplatform.repository;

import com.cdcplatform.model.PageResult;
import com.cdcplatform.model.dev.DevFlow;
import com.cdcplatform.model.dev.DevFlowFolder;
import com.cdcplatform.model.dev.DevFlowRun;
import com.cdcplatform.model.dev.DevGraph;
import com.cdcplatform.model.dev.DevNodeRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 数据开发的持久层。与 {@code TaskRepository} 同一套约定：ID 取自 {@code cdc_config_id} 序列，
 * 分页在 SQL 里做（{@code PageResult.of} 会把 size 夹到 100，不适合当分页实现）。
 */
@Repository
public class DevFlowRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DevFlowRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- flows

    /**
     * @param view 顶部汇总卡片的筛选维度：running / failed / succeeded，其余值（含 all）不按运行状态过滤
     */
    public List<DevFlow> flows(String q, String status, String view) {
        return flows(q, status, view, null, false);
    }

    /**
     * @param folderIds 只看这些文件夹（调用方负责把子树 id 一并算进来）；null/空表示不按文件夹过滤
     * @param rootOnly  只看根目录（folder_id IS NULL）
     */
    public List<DevFlow> flows(String q, String status, String view, List<Long> folderIds, boolean rootOnly) {
        String lastRunStatus = switch (view == null ? "" : view.trim().toLowerCase(Locale.ROOT)) {
            case "running" -> "RUNNING";
            case "failed" -> "FAILED";
            case "succeeded" -> "SUCCEEDED";
            default -> null;
        };
        var sql = new StringBuilder("SELECT * FROM dev_flow WHERE 1=1");
        if (q != null && !q.isBlank()) {
            sql.append(" AND LOWER(name) LIKE :like");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
        }
        if (lastRunStatus != null) {
            sql.append(" AND last_run_status = :lastRunStatus");
        }
        if (rootOnly) {
            sql.append(" AND folder_id IS NULL");
        } else if (folderIds != null && !folderIds.isEmpty()) {
            // 手写占位符而不是传集合：JdbcClient 对集合参数的展开行为依赖版本，显式更稳
            sql.append(" AND folder_id IN (");
            for (int i = 0; i < folderIds.size(); i++) {
                sql.append(i > 0 ? ", :f" : ":f").append(i);
            }
            sql.append(')');
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        var spec = jdbc.sql(sql.toString());
        if (q != null && !q.isBlank()) {
            spec = spec.param("like", "%" + q.trim().toLowerCase() + "%");
        }
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        if (lastRunStatus != null) {
            spec = spec.param("lastRunStatus", lastRunStatus);
        }
        if (!rootOnly && folderIds != null) {
            for (int i = 0; i < folderIds.size(); i++) {
                spec = spec.param("f" + i, folderIds.get(i));
            }
        }
        return spec.query((rs, row) -> readFlow(rs)).list();
    }

    public DevFlow flow(long id) {
        return jdbc.sql("SELECT * FROM dev_flow WHERE id=:id").param("id", id)
                .query((rs, row) -> readFlow(rs)).optional()
                .orElseThrow(() -> notFound("flow", id));
    }

    public DevGraph graph(long id) {
        return parseGraph(flow(id).graphJson());
    }

    public DevGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return DevGraph.empty();
        }
        try {
            return json.readValue(graphJson, DevGraph.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("数据流的图定义无法解析：" + e.getOriginalMessage(), e);
        }
    }

    public String writeGraph(DevGraph graph) {
        try {
            return json.writeValueAsString(graph == null ? DevGraph.empty() : graph);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("数据流的图定义无法序列化", e);
        }
    }

    public long create(String name, String description, DevGraph graph, String actor) {
        return create(name, description, graph, actor, null);
    }

    public long create(String name, String description, DevGraph graph, String actor, Long folderId) {
        long id = nextId();
        var spec = jdbc.sql("""
                        INSERT INTO dev_flow (id, name, description, status, graph_json, version, created_by, folder_id)
                        VALUES (:id, :name, :description, 'DRAFT', :graph, 1, :actor, :folder)
                        """)
                .param("id", id).param("name", name).param("description", description == null ? "" : description)
                .param("graph", writeGraph(graph)).param("actor", actor);
        if (folderId == null) {
            spec.param("folder", null, java.sql.Types.BIGINT);
        } else {
            spec.param("folder", folderId);
        }
        spec.update();
        return id;
    }

    // ------------------------------------------------------------- folders

    public List<DevFlowFolder> folders() {
        return jdbc.sql("SELECT id, name, parent_id FROM dev_flow_folder ORDER BY LOWER(name)")
                .query((rs, row) -> new DevFlowFolder(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("parent_id", Long.class)))
                .list();
    }

    public DevFlowFolder folder(long id) {
        return jdbc.sql("SELECT id, name, parent_id FROM dev_flow_folder WHERE id=:id").param("id", id)
                .query((rs, row) -> new DevFlowFolder(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("parent_id", Long.class)))
                .optional().orElseThrow(() -> notFound("folder", id));
    }

    public long createFolder(String name, Long parentId) {
        if (parentId != null) {
            folder(parentId);
        }
        long id = nextId();
        if (parentId == null) {
            jdbc.sql("INSERT INTO dev_flow_folder (id, name) VALUES (:id, :name)")
                    .param("id", id).param("name", name).update();
        } else {
            jdbc.sql("INSERT INTO dev_flow_folder (id, name, parent_id) VALUES (:id, :name, :parent)")
                    .param("id", id).param("name", name).param("parent", parentId).update();
        }
        return id;
    }

    public void renameFolder(long id, String name) {
        int changed = jdbc.sql("UPDATE dev_flow_folder SET name=:name, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("name", name).update();
        if (changed != 1) {
            throw notFound("folder", id);
        }
    }

    /**
     * 删除文件夹：子文件夹与其中的数据流**上移到父级**，而不是连带删除。
     *
     * <p>删目录不该毁数据——用户清理结构时最不希望数据流跟着消失。
     */
    public void deleteFolder(long id) {
        DevFlowFolder folder = folder(id);
        if (folder.parentId() == null) {
            jdbc.sql("UPDATE dev_flow_folder SET parent_id=NULL, updated_at=CURRENT_TIMESTAMP WHERE parent_id=:id")
                    .param("id", id).update();
            jdbc.sql("UPDATE dev_flow SET folder_id=NULL WHERE folder_id=:id").param("id", id).update();
        } else {
            jdbc.sql("UPDATE dev_flow_folder SET parent_id=:parent, updated_at=CURRENT_TIMESTAMP WHERE parent_id=:id")
                    .param("id", id).param("parent", folder.parentId()).update();
            jdbc.sql("UPDATE dev_flow SET folder_id=:parent WHERE folder_id=:id")
                    .param("id", id).param("parent", folder.parentId()).update();
        }
        jdbc.sql("DELETE FROM dev_flow_folder WHERE id=:id").param("id", id).update();
    }

    /** 把数据流移到某个文件夹；folderId 为 null 表示移回根目录。 */
    public void moveFlow(long id, Long folderId) {
        if (folderId != null) {
            folder(folderId);
        }
        int changed = folderId == null
                ? jdbc.sql("UPDATE dev_flow SET folder_id=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                        .param("id", id).update()
                : jdbc.sql("UPDATE dev_flow SET folder_id=:folder, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                        .param("id", id).param("folder", folderId).update();
        if (changed != 1) {
            throw notFound("flow", id);
        }
    }

    public void update(long id, String name, String description, DevGraph graph) {
        int changed = jdbc.sql("""
                        UPDATE dev_flow
                           SET name=:name, description=:description, graph_json=:graph,
                               version=version+1, updated_at=CURRENT_TIMESTAMP
                         WHERE id=:id
                        """)
                .param("id", id).param("name", name).param("description", description == null ? "" : description)
                .param("graph", writeGraph(graph))
                .update();
        if (changed != 1) {
            throw notFound("flow", id);
        }
    }

    public void rename(long id, String name, String description) {
        int changed = jdbc.sql("""
                        UPDATE dev_flow SET name=:name, description=:description, updated_at=CURRENT_TIMESTAMP
                         WHERE id=:id
                        """)
                .param("id", id).param("name", name).param("description", description == null ? "" : description)
                .update();
        if (changed != 1) {
            throw notFound("flow", id);
        }
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM dev_flow WHERE id=:id").param("id", id).update();
    }

    // ------------------------------------------------------------ flow runs

    public long startRun(long flowId, int flowVersion, String actor, int nodeTotal, String graphSnapshot) {
        long id = nextId();
        jdbc.sql("""
                        INSERT INTO dev_flow_run (id, flow_id, flow_version, status, trigger_type, actor,
                                                  node_total, graph_snapshot)
                        VALUES (:id, :flow, :version, 'RUNNING', 'MANUAL', :actor, :nodes, :graph)
                        """)
                .param("id", id).param("flow", flowId).param("version", flowVersion)
                .param("actor", actor).param("nodes", nodeTotal).param("graph", graphSnapshot)
                .update();
        jdbc.sql("UPDATE dev_flow SET status='ACTIVE', last_run_status='RUNNING', last_run_at=CURRENT_TIMESTAMP, "
                + "updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", flowId).update();
        return id;
    }

    public void finishRun(long runId, String status, long rowsWritten, String message, int succeeded, long durationMs) {
        jdbc.sql("""
                        UPDATE dev_flow_run
                           SET status=:status, ended_at=CURRENT_TIMESTAMP, duration_ms=:duration,
                               rows_written=:rows, message=:message, node_succeeded=:succeeded
                         WHERE id=:id
                        """)
                .param("id", runId).param("status", status).param("duration", durationMs)
                .param("rows", rowsWritten).param("message", truncate(message)).param("succeeded", succeeded)
                .update();
    }

    public void markFlow(long flowId, String status, long rows) {
        jdbc.sql("""
                        UPDATE dev_flow
                           SET last_run_status=:status, last_run_at=CURRENT_TIMESTAMP, last_run_rows=:rows,
                               updated_at=CURRENT_TIMESTAMP
                         WHERE id=:id
                        """)
                .param("id", flowId).param("status", status).param("rows", rows).update();
    }

    public PageResult<DevFlowRun> runs(long flowId, int page, int size) {
        flow(flowId);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 100));
        long total = jdbc.sql("SELECT COUNT(*) FROM dev_flow_run WHERE flow_id=:id")
                .param("id", flowId).query(Long.class).single();
        var items = jdbc.sql("""
                        SELECT * FROM dev_flow_run WHERE flow_id=:id
                         ORDER BY started_at DESC, id DESC LIMIT :size OFFSET :offset
                        """)
                .param("id", flowId).param("size", safeSize).param("offset", (long) safePage * safeSize)
                .query((rs, row) -> readRun(rs)).list();
        return new PageResult<>(items, total, safePage, safeSize,
                total == 0 ? 0 : (int) Math.ceil((double) total / safeSize));
    }

    public DevFlowRun run(long flowId, long runId) {
        return jdbc.sql("SELECT * FROM dev_flow_run WHERE id=:id AND flow_id=:flow")
                .param("id", runId).param("flow", flowId)
                .query((rs, row) -> readRun(rs)).optional()
                .orElseThrow(() -> notFound("run", runId));
    }

    public String graphSnapshot(long runId) {
        return jdbc.sql("SELECT graph_snapshot FROM dev_flow_run WHERE id=:id").param("id", runId)
                .query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------ node runs

    public long startNodeRun(long runId, String nodeKey, String nodeType, String nodeName) {
        long id = nextId();
        jdbc.sql("""
                        INSERT INTO dev_node_run (id, run_id, node_key, node_type, node_name, status)
                        VALUES (:id, :run, :key, :type, :name, 'RUNNING')
                        """)
                .param("id", id).param("run", runId).param("key", nodeKey)
                .param("type", nodeType).param("name", truncate(nodeName)).update();
        return id;
    }

    public void finishNodeRun(long id, String status, long rowsRead, long rowsWritten, String message, long durationMs) {
        jdbc.sql("""
                        UPDATE dev_node_run
                           SET status=:status, ended_at=CURRENT_TIMESTAMP, duration_ms=:duration,
                               rows_read=:read, rows_written=:written, message=:message
                         WHERE id=:id
                        """)
                .param("id", id).param("status", status).param("duration", durationMs)
                .param("read", rowsRead).param("written", rowsWritten).param("message", truncate(message))
                .update();
    }

    public List<DevNodeRun> nodeRuns(long runId) {
        return jdbc.sql("SELECT * FROM dev_node_run WHERE run_id=:id ORDER BY id")
                .param("id", runId).query((rs, row) -> readNodeRun(rs)).list();
    }

    // --------------------------------------------------------------- summary

    /** 文件夹过滤片段；列表与统计共用同一套占位符命名。 */
    private static String folderClause(List<Long> folderIds, boolean rootOnly) {
        if (rootOnly) {
            return " AND folder_id IS NULL";
        }
        if (folderIds == null || folderIds.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder(" AND folder_id IN (");
        for (int i = 0; i < folderIds.size(); i++) {
            clause.append(i > 0 ? ", :f" : ":f").append(i);
        }
        return clause.append(')').toString();
    }

    private long count(String sql, List<Long> folderIds, boolean rootOnly) {
        var spec = jdbc.sql(sql);
        if (!rootOnly && folderIds != null) {
            for (int i = 0; i < folderIds.size(); i++) {
                spec = spec.param("f" + i, folderIds.get(i));
            }
        }
        return spec.query(Long.class).single();
    }

    public Summary summary() {
        return summary(null, false);
    }

    /** 与列表同一口径：带上文件夹过滤，避免「卡片说 5 条、列表 0 条」。 */
    public Summary summary(List<Long> folderIds, boolean rootOnly) {
        String filter = folderClause(folderIds, rootOnly);
        long total = count("SELECT COUNT(*) FROM dev_flow WHERE 1=1" + filter, folderIds, rootOnly);
        // 与「运行中」卡片的筛选口径保持一致：按数据流当前状态计数，而不是按运行记录条数
        long running = count("SELECT COUNT(*) FROM dev_flow WHERE last_run_status='RUNNING'" + filter, folderIds, rootOnly);
        String inFolder = filter.isEmpty() ? "" : " AND r.flow_id IN (SELECT id FROM dev_flow WHERE 1=1" + filter + ")";
        long failed = count("""
                        SELECT COUNT(*) FROM dev_flow_run r
                         WHERE r.status='FAILED'
                           AND r.id = (SELECT MAX(x.id) FROM dev_flow_run x WHERE x.flow_id = r.flow_id)"""
                + inFolder, folderIds, rootOnly);
        long succeeded = count("""
                        SELECT COUNT(*) FROM dev_flow_run r
                         WHERE r.status='SUCCEEDED'
                           AND r.id = (SELECT MAX(x.id) FROM dev_flow_run x WHERE x.flow_id = r.flow_id)"""
                + inFolder, folderIds, rootOnly);
        return new Summary(total, running, failed, succeeded);
    }

    public record Summary(long total, long running, long failed, long succeeded) {
    }

    // ----------------------------------------------------------------- rows

    private DevFlow readFlow(ResultSet rs) {
        try {
            return new DevFlow(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                    rs.getString("status"), rs.getString("graph_json"), rs.getInt("version"),
                    rs.getString("last_run_status"), rs.getObject("last_run_at", OffsetDateTime.class),
                    rs.getLong("last_run_rows"), rs.getString("created_by"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    rs.getObject("folder_id", Long.class));
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据流失败", e);
        }
    }

    private DevFlowRun readRun(ResultSet rs) {
        try {
            return new DevFlowRun(rs.getLong("id"), rs.getLong("flow_id"), rs.getInt("flow_version"),
                    rs.getString("status"), rs.getString("trigger_type"), rs.getString("actor"),
                    rs.getObject("started_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class),
                    nullableLong(rs, "duration_ms"), rs.getInt("node_total"), rs.getInt("node_succeeded"),
                    rs.getLong("rows_written"), rs.getString("message"));
        } catch (SQLException e) {
            throw new IllegalStateException("读取运行记录失败", e);
        }
    }

    private DevNodeRun readNodeRun(ResultSet rs) {
        try {
            return new DevNodeRun(rs.getLong("id"), rs.getLong("run_id"), rs.getString("node_key"),
                    rs.getString("node_type"), rs.getString("node_name"), rs.getString("status"),
                    rs.getObject("started_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class),
                    nullableLong(rs, "duration_ms"), rs.getLong("rows_read"), rs.getLong("rows_written"),
                    rs.getString("message"));
        } catch (SQLException e) {
            throw new IllegalStateException("读取节点运行记录失败", e);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private long nextId() {
        return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();
    }

    private IllegalArgumentException notFound(String type, long id) {
        return new IllegalArgumentException(type + " " + id + " not found");
    }
}
