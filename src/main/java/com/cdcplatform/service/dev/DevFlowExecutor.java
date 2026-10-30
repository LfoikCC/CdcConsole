package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.dev.DevFlow;
import com.cdcplatform.model.dev.DevGraph;
import com.cdcplatform.model.dev.DevGraph.DevNode;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DevFlowRepository;
import com.cdcplatform.service.dev.DevNodeReader.ReadResult;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 数据流执行器：校验 → 建运行记录 → 按拓扑序逐节点执行 → 记录每节点结果 → 清理临时表。
 *
 * <p>失败即停。一个节点挂了还继续跑下游，只会产出更难排查的次生错误。
 */
@Service
public class DevFlowExecutor {

    private static final Logger log = LoggerFactory.getLogger(DevFlowExecutor.class);

    private final DevFlowRepository flows;
    private final ConfigRepository configs;
    private final DevGraphValidator validator;
    private final DevNodeReader reader;
    private final DevOperatorSql operators;
    private final DevTargetWriter writer;
    private final DevTempTableStore store;
    private final DevProperties props;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    public DevFlowExecutor(DevFlowRepository flows, ConfigRepository configs, DevGraphValidator validator,
                           DevNodeReader reader, DevOperatorSql operators, DevTargetWriter writer,
                           DevTempTableStore store, DevProperties props) {
        this.flows = flows;
        this.configs = configs;
        this.validator = validator;
        this.reader = reader;
        this.operators = operators;
        this.writer = writer;
        this.store = store;
        this.props = props;
        this.permits = new Semaphore(Math.max(1, props.getMaxConcurrentRuns()));
    }

    public record PreviewResult(List<String> columns, List<List<Object>> rows, boolean truncated,
                                long elapsedMs, long rowsRead, String note) {
    }

    /** 提交一次运行，立即返回 runId。校验不通过时抛 {@link IllegalArgumentException}（→ 400）。 */
    public long submit(long flowId, String actor) {
        DevFlow flow = flows.flow(flowId);
        DevGraph graph = flows.parseGraph(flow.graphJson());
        var report = validator.validate(graph, true);
        if (!report.valid()) {
            String detail = report.errors().stream()
                    .map(i -> (i.nodeKey() == null ? "" : i.nodeKey() + "：") + i.message())
                    .reduce((a, b) -> a + "；" + b).orElse("数据流校验未通过");
            throw new IllegalArgumentException(detail);
        }
        long runId = flows.startRun(flowId, flow.version(), actor, graph.nodes().size(), flow.graphJson());
        pool.submit(() -> {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                flows.finishRun(runId, "CANCELED", 0, "运行被中断", 0, 0);
                return;
            }
            try {
                execute(runId, flowId, graph, report.order());
            } finally {
                permits.release();
                // 每次运行都清一次：临时表是持久 schema，留着会让 cdc.mv.db 一直长
                try {
                    store.dropAll();
                } catch (Exception e) {
                    log.warn("清理临时表失败：{}", e.getMessage());
                }
            }
        });
        return runId;
    }

    private void execute(long runId, long flowId, DevGraph graph, List<String> order) {
        long started = System.currentTimeMillis();
        Map<String, DevNode> byId = new HashMap<>();
        graph.nodes().forEach(node -> byId.put(node.id(), node));

        Map<String, ReadResult> outputs = new HashMap<>();
        int succeeded = 0;
        long written = 0;

        for (String nodeId : order) {
            DevNode node = byId.get(nodeId);
            if (node == null) {
                continue;
            }
            long nodeStarted = System.currentTimeMillis();
            long nodeRunId = flows.startNodeRun(runId, node.id(), node.type(), node.displayName());
            try {
                NodeResult result = runNode(node, graph, outputs, runId);
                outputs.put(node.id(), result.read());
                written += result.written();
                succeeded++;
                flows.finishNodeRun(nodeRunId, "SUCCEEDED", result.read().rows(), result.written(),
                        result.note(), System.currentTimeMillis() - nodeStarted);
            } catch (Exception e) {
                String message = rootMessage(e);
                flows.finishNodeRun(nodeRunId, "FAILED", 0, 0, message, System.currentTimeMillis() - nodeStarted);
                flows.finishRun(runId, "FAILED", written, message, succeeded, System.currentTimeMillis() - started);
                flows.markFlow(flowId, "FAILED", written);
                log.warn("数据流 {} 的节点 {} 执行失败：{}", flowId, node.id(), message);
                return;
            }
        }

        flows.finishRun(runId, "SUCCEEDED", written, null, succeeded, System.currentTimeMillis() - started);
        flows.markFlow(flowId, "SUCCEEDED", written);
    }

    private record NodeResult(ReadResult read, long written, String note) {
    }

    private NodeResult runNode(DevNode node, DevGraph graph, Map<String, ReadResult> outputs, long scope) {
        Map<String, Object> config = node.configOrEmpty();
        return switch (String.valueOf(node.type())) {
            case DevGraphValidator.TYPE_DB_INPUT -> {
                SourceConfig source = source(requiredId(config, "sourceId", node));
                String database = text(config.get("database"));
                String table = text(config.get("table"));
                List<String> columns = stringList(config.get("columns"));
                ReadResult result = reader.readTable(source, database, table, columns,
                        text(config.get("where")), scope, node.id(), props.getMaxRowsPerNode());
                yield new NodeResult(result, 0, null);
            }
            case DevGraphValidator.TYPE_SQL_INPUT -> {
                SourceConfig source = source(requiredId(config, "sourceId", node));
                ReadResult result = reader.readSql(source, text(config.get("database")),
                        text(config.get("sql")), scope, node.id(), props.getMaxRowsPerNode());
                yield new NodeResult(result, 0, null);
            }
            case DevGraphValidator.TYPE_OPERATOR -> {
                ReadResult upstream = upstream(node, graph, outputs);
                String target = store.tableName(scope, node.id());
                store.drop(target);
                var plan = operators.plan(text(config.get("operator")), config, upstream.table(), upstream.columns());
                store.create(target, plan.columns());
                long rows = store.materialize(target, plan.columns(), plan.selectSql(), plan.params());
                yield new NodeResult(new ReadResult(target, rows, plan.columns()), 0, null);
            }
            case DevGraphValidator.TYPE_DB_OUTPUT -> {
                ReadResult upstream = upstream(node, graph, outputs);
                TargetConfig target = target(requiredId(config, "targetId", node));
                String table = text(config.get("table"));
                var mode = switch (String.valueOf(config.getOrDefault("mode", "APPEND")).toUpperCase()) {
                    case "OVERWRITE" -> DevTargetWriter.WriteMode.OVERWRITE;
                    case "UPSERT" -> DevTargetWriter.WriteMode.UPSERT;
                    default -> DevTargetWriter.WriteMode.APPEND;
                };
                List<String> targetColumns = stringList(config.get("targetColumns"));
                List<String> keys = stringList(config.get("keyColumns"));
                var result = writer.write(target, table, mode, upstream.columns(), upstream.table(),
                        targetColumns, keys);
                yield new NodeResult(upstream, result.written(),
                        "写入 " + table + "：" + result.written() + " 行");
            }
            default -> throw new IllegalArgumentException("未知节点类型：" + node.type());
        };
    }

    // -------------------------------------------------------------- preview

    /** 预览某个节点：只跑它的祖先子图，输入节点最多扫 previewScanRows 行。 */
    public PreviewResult preview(long flowId, String nodeKey, int limit) {
        long started = System.currentTimeMillis();
        DevFlow flow = flows.flow(flowId);
        DevGraph graph = flows.parseGraph(flow.graphJson());
        DevNode target = graph.nodes().stream()
                .filter(n -> nodeKey.equals(n.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("节点 " + nodeKey + " 不存在"));

        List<String> order = ancestorsOf(graph, nodeKey);
        var report = validator.validate(graph, true);
        if (!report.valid()) {
            // 预览允许配置不完整，但拓扑必须成立，否则无法确定执行顺序
            boolean broken = report.errors().stream().anyMatch(i -> "cycle".equals(i.field()));
            if (broken) {
                throw new IllegalArgumentException("数据流存在环，无法预览");
            }
        }

        long scope = System.nanoTime();
        Map<String, ReadResult> outputs = new HashMap<>();
        Map<String, DevNode> byId = new HashMap<>();
        graph.nodes().forEach(n -> byId.put(n.id(), n));
        List<String> created = new ArrayList<>();

        try {
            for (String id : order) {
                DevNode node = byId.get(id);
                if (node == null) {
                    continue;
                }
                if (DevGraphValidator.TYPE_DB_OUTPUT.equals(node.type())) {
                    // 输出节点在预览里绝不落库：预览必须是只读的
                    continue;
                }
                NodeResult result = runNodePreview(node, graph, outputs, scope);
                outputs.put(id, result.read());
                created.add(result.read().table());
            }

            if (DevGraphValidator.TYPE_DB_OUTPUT.equals(target.type())) {
                ReadResult upstream = outputs.get(upstreamId(target, graph));
                if (upstream == null) {
                    throw new IllegalArgumentException("输出节点没有可预览的上游数据");
                }
                var rows = store.preview(upstream.table(), Math.max(1, limit));
                String note = "输出节点预览不会写入目标库；目标：" + text(target.configOrEmpty().get("table"))
                        + "，模式：" + text(target.configOrEmpty().getOrDefault("mode", "APPEND"));
                return new PreviewResult(rows.columns(), rows.rows(), rows.truncated(),
                        System.currentTimeMillis() - started, upstream.rows(), note);
            }

            ReadResult self = outputs.get(nodeKey);
            if (self == null) {
                throw new IllegalArgumentException("节点 " + nodeKey + " 没有产出");
            }
            var rows = store.preview(self.table(), Math.max(1, limit));
            return new PreviewResult(rows.columns(), rows.rows(), rows.truncated(),
                    System.currentTimeMillis() - started, self.rows(), null);
        } finally {
            created.forEach(store::drop);
        }
    }

    private NodeResult runNodePreview(DevNode node, DevGraph graph, Map<String, ReadResult> outputs, long scope) {
        int scan = Math.max(1, props.getPreviewScanRows());
        Map<String, Object> config = node.configOrEmpty();
        return switch (String.valueOf(node.type())) {
            case DevGraphValidator.TYPE_DB_INPUT -> {
                SourceConfig source = source(requiredId(config, "sourceId", node));
                yield new NodeResult(reader.readTable(source, text(config.get("database")),
                        text(config.get("table")), stringList(config.get("columns")),
                        text(config.get("where")), scope, node.id(), scan), 0, null);
            }
            case DevGraphValidator.TYPE_SQL_INPUT -> {
                SourceConfig source = source(requiredId(config, "sourceId", node));
                yield new NodeResult(reader.readSql(source, text(config.get("database")),
                        text(config.get("sql")), scope, node.id(), scan), 0, null);
            }
            case DevGraphValidator.TYPE_OPERATOR -> {
                ReadResult upstream = upstream(node, graph, outputs);
                String target = store.tableName(scope, node.id());
                store.drop(target);
                var plan = operators.plan(text(config.get("operator")), config, upstream.table(), upstream.columns());
                store.create(target, plan.columns());
                long rows = store.materialize(target, plan.columns(), plan.selectSql(), plan.params());
                yield new NodeResult(new ReadResult(target, rows, plan.columns()), 0, null);
            }
            default -> new NodeResult(new ReadResult(store.tableName(scope, node.id()), 0, List.of()), 0, null);
        };
    }

    /** 祖先子图的拓扑序（含自身）。 */
    private List<String> ancestorsOf(DevGraph graph, String nodeKey) {
        Set<String> needed = new LinkedHashSet<>();
        collect(graph, nodeKey, needed);
        Set<String> ids = new LinkedHashSet<>();
        graph.nodes().stream().filter(n -> needed.contains(n.id())).forEach(n -> ids.add(n.id()));
        var edges = graph.edges().stream()
                .filter(e -> needed.contains(e.from()) && needed.contains(e.to()))
                .toList();
        List<String> order = validator.topologicalOrder(ids, edges);
        if (order.size() != ids.size()) {
            throw new IllegalArgumentException("数据流存在环，无法预览");
        }
        return order;
    }

    private void collect(DevGraph graph, String id, Set<String> acc) {
        if (!acc.add(id)) {
            return;
        }
        for (var edge : graph.edges()) {
            if (id.equals(edge.to())) {
                collect(graph, edge.from(), acc);
            }
        }
    }

    private String upstreamId(DevNode node, DevGraph graph) {
        return graph.edges().stream()
                .filter(e -> node.id().equals(e.to()))
                .map(e -> e.from())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("节点 " + node.id() + " 没有上游"));
    }

    private ReadResult upstream(DevNode node, DevGraph graph, Map<String, ReadResult> outputs) {
        String id = upstreamId(node, graph);
        ReadResult result = outputs.get(id);
        if (result == null) {
            throw new IllegalStateException("节点 " + node.id() + " 的上游 " + id + " 还没有产出");
        }
        return result;
    }

    // ------------------------------------------------------------- helpers

    private SourceConfig source(long id) {
        SourceConfig source = configs.source(id, true);
        if (source == null) {
            throw new IllegalArgumentException("源连接 " + id + " 不存在");
        }
        return source;
    }

    private TargetConfig target(long id) {
        TargetConfig target = configs.target(id, true);
        if (target == null) {
            throw new IllegalArgumentException("目标连接 " + id + " 不存在");
        }
        return target;
    }

    private static long requiredId(Map<String, Object> config, String field, DevNode node) {
        Object value = config.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("节点 " + node.id() + " 的 " + field + " 不是有效 ID");
            }
        }
        throw new IllegalArgumentException("节点 " + node.id() + " 缺少 " + field);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = text(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return result;
        }
        return List.of();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
