package com.cdcplatform.repository;

import com.cdcplatform.model.PageResult;
import com.cdcplatform.model.dev.DevFlow;
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
        long id = nextId();
        jdbc.sql("""
                        INSERT INTO dev_flow (id, name, description, status, graph_json, version, created_by)
                        VALUES (:id, :name, :description, 'DRAFT', :graph, 1, :actor)
                        """)
                .param("id", id).param("name", name).param("description", description == null ? "" : description)
                .param("graph", writeGraph(graph)).param("actor", actor)
                .update();
        return id;
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

    public Summary summary() {
        long total = jdbc.sql("SELECT COUNT(*) FROM dev_flow").query(Long.class).single();
        // 与「运行中」卡片的筛选口径保持一致：按数据流当前状态计数，而不是按运行记录条数
        long running = jdbc.sql("SELECT COUNT(*) FROM dev_flow WHERE last_run_status='RUNNING'")
                .query(Long.class).single();
        long failed = jdbc.sql("""
                        SELECT COUNT(*) FROM dev_flow_run r
                         WHERE r.status='FAILED'
                           AND r.id = (SELECT MAX(x.id) FROM dev_flow_run x WHERE x.flow_id = r.flow_id)
                        """).query(Long.class).single();
        long succeeded = jdbc.sql("""
                        SELECT COUNT(*) FROM dev_flow_run r
                         WHERE r.status='SUCCEEDED'
                           AND r.id = (SELECT MAX(x.id) FROM dev_flow_run x WHERE x.flow_id = r.flow_id)
                        """).query(Long.class).single();
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
                    rs.getObject("updated_at", OffsetDateTime.class));
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
