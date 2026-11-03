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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final DevDatasetReader datasetReader;
    private final DevTempTableStore store;
    private final DevProperties props;

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    /** 正在执行的运行 → 取消标志。只存在内存里，进程重启后残留的 RUNNING 由取消接口直接落库。 */
    private final Map<Long, RunToken> tokens = new ConcurrentHashMap<>();

    private record RunToken(long flowId, AtomicBoolean canceled) {
    }

    public static class RunCanceledException extends RuntimeException {
        public RunCanceledException() {
            super("运行已取消");
        }
    }

    public DevFlowExecutor(DevFlowRepository flows, ConfigRepository configs, DevGraphValidator validator,
                           DevNodeReader reader, DevOperatorSql operators, DevTargetWriter writer,
                           DevDatasetReader datasetReader, DevTempTableStore store, DevProperties props) {
        this.flows = flows;
        this.configs = configs;
        this.validator = validator;
        this.reader = reader;
        this.operators = operators;
        this.writer = writer;
        this.datasetReader = datasetReader;
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
        RunToken token = new RunToken(flowId, new AtomicBoolean(false));
        tokens.put(runId, token);
        pool.submit(() -> {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                flows.finishRun(runId, "CANCELED", 0, "运行被中断", 0, 0);
                tokens.remove(runId);
                return;
            }
            try {
                execute(runId, flowId, graph, report.order(), token.canceled());
            } finally {
                tokens.remove(runId);
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

    /** 取消一次运行；返回是否找到了内存中的运行标志。 */
    public boolean cancel(long flowId, long runId) {
        var run = flows.run(flowId, runId);
        if (!"RUNNING".equals(run.status()) && !"PENDING".equals(run.status())) {
            throw new IllegalStateException("运行已结束，无法取消");
        }
        RunToken token = tokens.get(runId);
        if (token != null) {
            token.canceled().set(true);
            return true;
        }
        // 进程重启后内存标志已丢失：直接把残留的 RUNNING 落成 CANCELED
        flows.finishRun(runId, "CANCELED", run.rowsWritten(), "运行已取消", run.nodeSucceeded(), 0);
        flows.markFlow(flowId, "CANCELED", run.rowsWritten());
        return false;
    }

    /** 取消某个数据流下所有在跑的运行（删除数据流前调用）。 */
    public int cancelFlow(long flowId) {
        int canceled = 0;
        for (var entry : tokens.entrySet()) {
            if (entry.getValue().flowId() == flowId && entry.getValue().canceled().compareAndSet(false, true)) {
                canceled++;
            }
        }
        return canceled;
    }

    private void execute(long runId, long flowId, DevGraph graph, List<String> order, AtomicBoolean canceled) {
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
            if (canceled.get()) {
                finishCanceled(runId, flowId, written, succeeded, started);
                return;
            }
            long nodeStarted = System.currentTimeMillis();
            long nodeRunId = flows.startNodeRun(runId, node.id(), node.type(), node.displayName());
            try {
                Runnable checkpoint = () -> {
                    if (canceled.get()) {
                        throw new RunCanceledException();
                    }
                };
                NodeResult result = runNode(node, graph, outputs, runId, checkpoint);
                outputs.put(node.id(), result.read());
                written += result.written();
                succeeded++;
                flows.finishNodeRun(nodeRunId, "SUCCEEDED", result.read().rows(), result.written(),
                        result.note(), System.currentTimeMillis() - nodeStarted);
            } catch (RunCanceledException e) {
                flows.finishNodeRun(nodeRunId, "CANCELED", 0, 0, "运行已取消",
                        System.currentTimeMillis() - nodeStarted);
                finishCanceled(runId, flowId, written, succeeded, started);
                log.info("数据流 {} 的运行 {} 已取消", flowId, runId);
                return;
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

    private void finishCanceled(long runId, long flowId, long written, int succeeded, long started) {
        flows.finishRun(runId, "CANCELED", written, "运行已取消", succeeded, System.currentTimeMillis() - started);
        flows.markFlow(flowId, "CANCELED", written);
    }

    private record NodeResult(ReadResult read, long written, String note) {
    }

    /**
     * 算子节点：按连线顺序取上游（双输入算子先连的为左、后连的为右）。
     *
     * <p>SQL 算子的输出列只能执行后从结果集元数据读，所以单独走 {@link DevTempTableStore#runSql}，
     * 不做静态列推导。
     */
    private NodeResult runOperator(DevNode node, DevGraph graph, Map<String, ReadResult> outputs,
                                   long scope, int maxRows) {
        Map<String, Object> config = node.configOrEmpty();
        List<ReadResult> ups = upstreams(node, graph, outputs);
        ReadResult left = ups.get(0);
        ReadResult right = ups.size() > 1 ? ups.get(1) : null;
        String target = store.tableName(scope, node.id());
        store.drop(target);

        String operator = text(config.get("operator"));
        if ("SQL".equalsIgnoreCase(operator)) {
            String sql = text(config.get("sql"));
            if (sql == null) {
                throw new IllegalArgumentException("SQL 算子没有填写 SQL");
            }
            // 用视图把物理列名 C1..Cn 映射回业务列名，用户才能写 SELECT id 而不是 SELECT "C1"
            String view = target + "_V";
            store.createView(view, left.table(), left.columns());
            try {
                var result = store.runSql(target, sql.replace("{{input}}", view), maxRows, left.columns());
                return new NodeResult(new ReadResult(result.table(), result.rows(), result.columns()), 0, null);
            } finally {
                store.dropView(view);
            }
        }

        var plan = operators.plan(operator, config,
                new DevOperatorSql.Input(left.table(), left.columns()),
                right == null ? null : new DevOperatorSql.Input(right.table(), right.columns()));
        store.create(target, plan.columns());
        long rows = store.materialize(target, plan.columns(), plan.selectSql(), plan.params());
        return new NodeResult(new ReadResult(target, rows, plan.columns()), 0, null);
    }

    private NodeResult runNode(DevNode node, DevGraph graph, Map<String, ReadResult> outputs, long scope,
                               Runnable checkpoint) {
        Map<String, Object> config = node.configOrEmpty();
        return switch (String.valueOf(node.type())) {
            case DevGraphValidator.TYPE_DB_INPUT -> {
                SourceConfig source = source(requiredId(config, "sourceId", node));
                // 同一个节点支持两种取数方式：选表 / 自定义 SQL
                if (DevGraphValidator.usesSql(config)) {
                    ReadResult result = reader.readSql(source, text(config.get("database")),
                            text(config.get("sql")), scope, node.id(), props.getMaxRowsPerNode());
                    yield new NodeResult(result, 0, null);
                }
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
            case DevGraphValidator.TYPE_OPERATOR -> runOperator(node, graph, outputs, scope, props.getMaxRowsPerNode());
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
                        targetColumns, keys, checkpoint);
                yield new NodeResult(upstream, result.written(),
                        "写入 " + table + "：" + result.written() + " 行");
            }
            case DevGraphValidator.TYPE_DATASET_INPUT -> {
                long datasetId = requiredId(config, "datasetId", node);
                ReadResult result = datasetReader.read(datasetId, scope, node.id(), props.getMaxRowsPerNode());
                yield new NodeResult(result, 0, null);
            }
            case DevGraphValidator.TYPE_DATASET_OUTPUT -> {
                long datasetId = requiredId(config, "datasetId", node);
                ReadResult upstream = upstream(node, graph, outputs);
                long rows = datasetReader.write(datasetId, text(config.getOrDefault("mode", "OVERWRITE")), upstream);
                yield new NodeResult(upstream, rows, "写入数据集：" + rows + " 行");
            }
            default -> throw new IllegalArgumentException("未知节点类型：" + node.type());
        };
    }

    // -------------------------------------------------------------- preview

    /** 预览某个节点：只跑它的祖先子图，输入节点最多扫 previewScanRows 行。 */
    public PreviewResult preview(long flowId, String nodeKey, int limit) {
        long started = System.currentTimeMillis();
        // 预览固定最多 100 行：够看清数据形态，又不至于把大宽表整页渲染到抽屉里
        int safeLimit = Math.max(1, Math.min(limit, 100));
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
                if (DevGraphValidator.TYPE_DB_OUTPUT.equals(node.type())
                        || DevGraphValidator.TYPE_DATASET_OUTPUT.equals(node.type())) {
                    // 输出节点在预览里绝不落库：预览必须是只读的
                    continue;
                }
                NodeResult result = runNodePreview(node, graph, outputs, scope);
                outputs.put(id, result.read());
                created.add(result.read().table());
            }

            if (DevGraphValidator.TYPE_DB_OUTPUT.equals(target.type())
                    || DevGraphValidator.TYPE_DATASET_OUTPUT.equals(target.type())) {
                ReadResult upstream = outputs.get(upstreamId(target, graph));
                if (upstream == null) {
                    throw new IllegalArgumentException("输出节点没有可预览的上游数据");
                }
                var rows = store.preview(upstream.table(), safeLimit);
                String note = DevGraphValidator.TYPE_DATASET_OUTPUT.equals(target.type())
                        ? "数据集输出预览不会写入数据集；数据集 ID：" + text(target.configOrEmpty().get("datasetId"))
                        + "，模式：" + text(target.configOrEmpty().getOrDefault("mode", "OVERWRITE"))
                        : "输出节点预览不会写入目标库；目标：" + text(target.configOrEmpty().get("table"))
                        + "，模式：" + text(target.configOrEmpty().getOrDefault("mode", "APPEND"));
                return new PreviewResult(rows.columns(), rows.rows(), rows.truncated(),
                        System.currentTimeMillis() - started, upstream.rows(), note);
            }

            ReadResult self = outputs.get(nodeKey);
            if (self == null) {
                throw new IllegalArgumentException("节点 " + nodeKey + " 没有产出");
            }
            var rows = store.preview(self.table(), safeLimit);
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
                if (DevGraphValidator.usesSql(config)) {
                    yield new NodeResult(reader.readSql(source, text(config.get("database")),
                            text(config.get("sql")), scope, node.id(), scan), 0, null);
                }
                yield new NodeResult(reader.readTable(source, text(config.get("database")),
                        text(config.get("table")), stringList(config.get("columns")),
                        text(config.get("where")), scope, node.id(), scan), 0, null);
            }
            case DevGraphValidator.TYPE_SQL_INPUT -> {
                SourceConfig source = source(requiredId(config, "sourceId", node));
                yield new NodeResult(reader.readSql(source, text(config.get("database")),
                        text(config.get("sql")), scope, node.id(), scan), 0, null);
            }
            case DevGraphValidator.TYPE_OPERATOR -> runOperator(node, graph, outputs, scope, scan);
            case DevGraphValidator.TYPE_DATASET_INPUT -> {
                long datasetId = requiredId(config, "datasetId", node);
                yield new NodeResult(datasetReader.read(datasetId, scope, node.id(), scan), 0, null);
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
        return upstreams(node, graph, outputs).get(0);
    }

    /** 按 {@code graph.edges()} 的顺序返回全部上游产出；双输入算子靠这个顺序区分左、右。 */
    private List<ReadResult> upstreams(DevNode node, DevGraph graph, Map<String, ReadResult> outputs) {
        List<String> ids = graph.edges().stream()
                .filter(edge -> node.id().equals(edge.to()))
                .map(edge -> edge.from())
                .toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("节点 " + node.id() + " 没有上游");
        }
        List<ReadResult> results = new ArrayList<>();
        for (String id : ids) {
            ReadResult result = outputs.get(id);
            if (result == null) {
                throw new IllegalStateException("节点 " + node.id() + " 的上游 " + id + " 还没有产出");
            }
            results.add(result);
        }
        return results;
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
