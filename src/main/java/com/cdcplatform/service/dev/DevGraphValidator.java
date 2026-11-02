package com.cdcplatform.service.dev;

import com.cdcplatform.model.dev.DevGraph;
import com.cdcplatform.model.dev.DevGraph.DevEdge;
import com.cdcplatform.model.dev.DevGraph.DevNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据流的结构校验与拓扑排序。
 *
 * <p>这里的规则不只是「看起来对」，每一条都对应一个具体的执行期故障：
 * <ul>
 *   <li>环 → 拓扑排序无解，执行会卡死在第一层</li>
 *   <li>没有输出节点 → 跑一遍什么也没发生，用户会以为成功了</li>
 *   <li>非输入节点入度为 0 → 它没有上游数据，读不到任何行</li>
 *   <li>输出节点多入边 → 本阶段语义未定义，宁可拒绝也不猜</li>
 * </ul>
 */
@Service
public class DevGraphValidator {

    public static final String TYPE_DB_INPUT = "DB_INPUT";
    public static final String TYPE_SQL_INPUT = "SQL_INPUT";
    public static final String TYPE_OPERATOR = "OPERATOR";
    public static final String TYPE_DB_OUTPUT = "DB_OUTPUT";
    public static final String TYPE_DATASET_INPUT = "DATASET_INPUT";
    public static final String TYPE_DATASET_OUTPUT = "DATASET_OUTPUT";

    private static final Set<String> TYPES = Set.of(TYPE_DB_INPUT, TYPE_SQL_INPUT, TYPE_OPERATOR,
            TYPE_DB_OUTPUT, TYPE_DATASET_INPUT, TYPE_DATASET_OUTPUT);

    /** 与前端调色板、DevOperatorSql 的分发保持一致；大小写不敏感，统一按大写比对。 */
    private static final Set<String> OPERATORS = Set.of(
            "PROJECT", "FILTER", "SORT", "DEDUP",
            "AGGREGATE", "CALCULATE", "REPLACE_VALUE", "REPLACE_NULL", "MERGE_COLUMNS",
            "UNION", "JOIN", "PIVOT", "UNPIVOT", "SQL");

    private static final Set<String> AGG_FUNCTIONS = Set.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX");

    public record Issue(String nodeKey, String field, String message) {
    }

    public record Report(boolean valid, List<Issue> errors, List<String> order) {
    }

    /** 只做结构校验，不碰数据库；连接是否存在由执行器在运行前查。 */
    public Report validate(DevGraph graph, boolean forExecution) {
        List<Issue> errors = new ArrayList<>();
        List<DevNode> nodes = graph == null ? List.of() : graph.nodes();
        List<DevEdge> edges = graph == null ? List.of() : graph.edges();

        if (nodes.isEmpty()) {
            errors.add(new Issue(null, "graph", "数据流还没有节点"));
            return new Report(false, errors, List.of());
        }

        Map<String, DevNode> byId = new LinkedHashMap<>();
        for (DevNode node : nodes) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add(new Issue(null, "id", "存在没有 id 的节点"));
                continue;
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                errors.add(new Issue(node.id(), "id", "节点 id 重复：" + node.id()));
            }
            if (node.type() == null || !TYPES.contains(node.type())) {
                errors.add(new Issue(node.id(), "type", "节点 " + node.id() + " 的类型无法识别：" + node.type()));
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        byId.keySet().forEach(id -> {
            inDegree.put(id, 0);
            downstream.put(id, new ArrayList<>());
        });

        Set<String> seenEdges = new HashSet<>();
        for (DevEdge edge : edges) {
            if (edge.from() == null || edge.to() == null
                    || !byId.containsKey(edge.from()) || !byId.containsKey(edge.to())) {
                errors.add(new Issue(edge.from(), "edge", "连线指向了不存在的节点：" + edge.from() + " → " + edge.to()));
                continue;
            }
            if (edge.from().equals(edge.to())) {
                errors.add(new Issue(edge.from(), "edge", "节点不能连到自己：" + edge.from()));
                continue;
            }
            if (!seenEdges.add(edge.from() + "->" + edge.to())) {
                errors.add(new Issue(edge.from(), "edge", "重复的连线：" + edge.from() + " → " + edge.to()));
                continue;
            }
            inDegree.merge(edge.to(), 1, Integer::sum);
            downstream.get(edge.from()).add(edge.to());
        }

        boolean hasOutput = byId.values().stream()
                .anyMatch(n -> TYPE_DB_OUTPUT.equals(n.type()) || TYPE_DATASET_OUTPUT.equals(n.type()));
        if (!hasOutput) {
            errors.add(new Issue(null, "graph", "数据流缺少数据库输出节点，运行不会产生任何结果"));
        }

        for (DevNode node : byId.values()) {
            int degree = inDegree.getOrDefault(node.id(), 0);
            boolean input = TYPE_DB_INPUT.equals(node.type()) || TYPE_SQL_INPUT.equals(node.type())
                    || TYPE_DATASET_INPUT.equals(node.type());
            int required = requiredInputs(node);
            if (input && degree > 0) {
                errors.add(new Issue(node.id(), "edge", "输入节点 " + node.id() + " 不能有上游"));
            }
            if (!input && degree == 0) {
                errors.add(new Issue(node.id(), "edge", "节点 " + node.id() + " 缺少上游输入"));
            }
            if (!input && degree > required) {
                errors.add(new Issue(node.id(), "edge", required == 2
                        ? "节点 " + node.id() + " 需要两个上游（左、右），当前有 " + degree + " 条入边"
                        : "节点 " + node.id() + " 有多个上游；每个节点只接受一条入边"));
            }
            if (!input && required == 2 && degree == 1) {
                errors.add(new Issue(node.id(), "edge",
                        "节点 " + node.id() + " 需要两个上游（左、右），当前只有 1 条入边"));
            }
            if (forExecution) {
                validateConfig(node, errors);
            }
        }

        List<String> order = topologicalOrder(byId.keySet(), edges);
        if (order.size() != byId.size() && errors.stream().noneMatch(i -> "cycle".equals(i.field()))) {
            errors.add(new Issue(null, "cycle", "数据流存在环，无法确定执行顺序"));
        }

        return new Report(errors.isEmpty(), List.copyOf(errors), List.copyOf(order));
    }

    /** Kahn 拓扑排序；有环时返回的长度会小于节点数。 */
    public List<String> topologicalOrder(Set<String> ids, List<DevEdge> edges) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        ids.forEach(id -> {
            inDegree.put(id, 0);
            downstream.put(id, new ArrayList<>());
        });
        for (DevEdge edge : edges) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to()) || edge.from().equals(edge.to())) {
                continue;
            }
            inDegree.merge(edge.to(), 1, Integer::sum);
            downstream.get(edge.from()).add(edge.to());
        }

        // 用 LinkedHashSet 当队列，保证同样的图每次得到同样的顺序——运行记录才可对比。
        Set<String> ready = new LinkedHashSet<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            var iterator = ready.iterator();
            String id = iterator.next();
            iterator.remove();
            order.add(id);
            for (String next : downstream.getOrDefault(id, List.of())) {
                int degree = inDegree.merge(next, -1, Integer::sum);
                if (degree == 0) {
                    ready.add(next);
                }
            }
        }
        return order;
    }

    /** 是否存在环——前端连线时用同样的判据阻止「连成环」。 */
    public boolean wouldCycle(DevGraph graph, String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return true;
        }
        List<DevEdge> edges = new ArrayList<>(graph.edges());
        edges.add(new DevEdge(from, to));
        Set<String> ids = new LinkedHashSet<>();
        graph.nodes().forEach(n -> ids.add(n.id()));
        return topologicalOrder(ids, edges).size() != ids.size();
    }

    private void validateConfig(DevNode node, List<Issue> errors) {
        Map<String, Object> config = node.configOrEmpty();
        switch (String.valueOf(node.type())) {
            case TYPE_DB_INPUT -> {
                requireNumber(config, "sourceId", node, errors, "请选择源连接");
                requireText(config, "table", node, errors, "请选择源表");
            }
            case TYPE_SQL_INPUT -> {
                requireNumber(config, "sourceId", node, errors, "请选择源连接");
                requireText(config, "sql", node, errors, "请填写 SQL");
            }
            case TYPE_OPERATOR -> {
                // 大小写归一：以前 "filter" 能过、"Filter" 报错，同一个算子两种命运
                Object raw = config.get("operator");
                String op = raw == null ? null : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
                if (op == null || op.isEmpty() || !OPERATORS.contains(op)) {
                    errors.add(new Issue(node.id(), "operator",
                            "节点 " + node.id() + " 的算子类型无法识别：" + raw));
                } else {
                    validateOperator(op, config, node, errors);
                }
            }
            case TYPE_DB_OUTPUT -> {
                requireNumber(config, "targetId", node, errors, "请选择目标连接");
                requireText(config, "table", node, errors, "请选择目标表");
                Object mode = config.get("mode");
                if (mode != null && !Set.of("OVERWRITE", "APPEND", "UPSERT").contains(String.valueOf(mode))) {
                    errors.add(new Issue(node.id(), "mode", "写入模式无法识别：" + mode));
                }
                if ("UPSERT".equals(String.valueOf(mode)) && asList(config.get("keyColumns")).isEmpty()) {
                    errors.add(new Issue(node.id(), "keyColumns", "按主键 upsert 需要至少一个主键列"));
                }
            }
            case TYPE_DATASET_INPUT -> requireNumber(config, "datasetId", node, errors, "请选择数据集");
            case TYPE_DATASET_OUTPUT -> {
                requireNumber(config, "datasetId", node, errors, "请选择数据集");
                Object mode = config.get("mode");
                if (mode != null && !Set.of("OVERWRITE", "APPEND").contains(String.valueOf(mode))) {
                    errors.add(new Issue(node.id(), "mode", "数据集写入只支持覆盖或追加"));
                }
            }
            default -> {
                // 类型已在前面报过
            }
        }
    }

    /**
     * 每个算子的必填项。
     *
     * <p>这里只拦「一定跑不起来」的配置：缺字段、缺函数、缺输出列名。列是否存在、类型是否可加
     * 要等上游预览拿到列之后才能判断，留给执行器报错——校验阶段过早访问数据库反而更慢。
     */
    private void validateOperator(String operator, Map<String, Object> config, DevNode node, List<Issue> errors) {
        switch (operator) {
            case "FILTER" -> {
                List<Object> conditions = asList(config.get("conditions"));
                if (conditions.isEmpty()) {
                    errors.add(new Issue(node.id(), "conditions", "过滤算子至少需要一个条件"));
                    return;
                }
                for (Object raw : conditions) {
                    if (!(raw instanceof Map<?, ?> condition)) {
                        continue;
                    }
                    if (textOf(condition.get("column")) == null) {
                        errors.add(new Issue(node.id(), "conditions", "过滤条件缺少字段"));
                    }
                    String op = textOf(condition.get("op"));
                    if (op == null) {
                        errors.add(new Issue(node.id(), "conditions", "过滤条件缺少比较符"));
                    } else if (!DevOperatorSql.COMPARATORS.contains(op.toUpperCase(Locale.ROOT))) {
                        errors.add(new Issue(node.id(), "conditions", "不支持的比较符：" + op));
                    }
                }
            }
            case "AGGREGATE" -> {
                List<Object> aggregates = asList(config.get("aggregates"));
                if (aggregates.isEmpty()) {
                    errors.add(new Issue(node.id(), "aggregates", "分组聚合至少需要一个聚合项"));
                    return;
                }
                for (Object raw : aggregates) {
                    if (!(raw instanceof Map<?, ?> item)) {
                        continue;
                    }
                    String function = textOf(item.get("function"));
                    if (function == null) {
                        errors.add(new Issue(node.id(), "aggregates", "聚合项缺少函数"));
                        continue;
                    }
                    String upper = function.toUpperCase(Locale.ROOT);
                    if (!AGG_FUNCTIONS.contains(upper)) {
                        errors.add(new Issue(node.id(), "aggregates", "不支持的聚合函数：" + function));
                    }
                    if (textOf(item.get("alias")) == null) {
                        errors.add(new Issue(node.id(), "aggregates", "聚合项缺少输出列名"));
                    }
                    if (textOf(item.get("column")) == null && !"COUNT".equals(upper)) {
                        errors.add(new Issue(node.id(), "aggregates", upper + " 需要指定字段"));
                    }
                }
            }
            case "CALCULATE" -> {
                requireText(config, "alias", node, errors, "请填写新列名");
                requireText(config, "expression", node, errors, "请填写计算表达式");
                String type = textOf(config.get("type"));
                if (type == null) {
                    errors.add(new Issue(node.id(), "type", "请选择计算结果类型"));
                } else if (!DevOperatorSql.CAST_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
                    errors.add(new Issue(node.id(), "type", "不支持的计算结果类型：" + type));
                }
                String expression = textOf(config.get("expression"));
                if (expression != null) {
                    // 语法错误不必等到运行；这条检查不需要上游列
                    try {
                        DevExpressionValidator.checkSyntax(expression);
                    } catch (IllegalArgumentException e) {
                        errors.add(new Issue(node.id(), "expression", e.getMessage()));
                    }
                }
            }
            case "REPLACE_VALUE" -> {
                List<Object> rules = asList(config.get("rules"));
                if (rules.isEmpty()) {
                    errors.add(new Issue(node.id(), "rules", "值替换至少需要一条规则"));
                    return;
                }
                for (Object raw : rules) {
                    if (!(raw instanceof Map<?, ?> rule)) {
                        continue;
                    }
                    String column = textOf(rule.get("column"));
                    if (column == null) {
                        errors.add(new Issue(node.id(), "rules", "值替换规则缺少字段"));
                    } else if (rule.get("from") == null) {
                        errors.add(new Issue(node.id(), "rules",
                                "字段 " + column + " 的原值不能为空（NULL 请用 Null 值替换算子）"));
                    }
                }
            }
            case "REPLACE_NULL" -> {
                List<Object> fills = asList(config.get("fills"));
                if (fills.isEmpty()) {
                    errors.add(new Issue(node.id(), "fills", "Null 值替换至少需要一个字段"));
                    return;
                }
                for (Object raw : fills) {
                    if (raw instanceof Map<?, ?> fill && textOf(fill.get("column")) == null) {
                        errors.add(new Issue(node.id(), "fills", "Null 值替换缺少字段"));
                    }
                }
            }
            case "MERGE_COLUMNS" -> {
                List<Object> columns = asList(config.get("columns"));
                long valid = columns.stream().filter(item -> textOf(item) != null).count();
                if (valid < 2) {
                    errors.add(new Issue(node.id(), "columns", "合并列至少需要两个字段"));
                }
                requireText(config, "alias", node, errors, "请填写合并后的列名");
            }
            case "UNION" -> {
                String mode = textOf(config.get("mode"));
                if (mode != null && !Set.of("ALL", "DISTINCT").contains(mode.toUpperCase(Locale.ROOT))) {
                    errors.add(new Issue(node.id(), "mode", "不支持的拼接方式：" + mode));
                }
            }
            case "JOIN" -> {
                String type = textOf(config.get("joinType"));
                if (type != null && !Set.of("INNER", "LEFT", "RIGHT").contains(type.toUpperCase(Locale.ROOT))) {
                    errors.add(new Issue(node.id(), "joinType", "不支持的关联类型：" + type));
                }
                List<Object> keys = asList(config.get("keys"));
                if (keys.isEmpty()) {
                    errors.add(new Issue(node.id(), "keys", "关联数据至少需要一个关联键"));
                    return;
                }
                for (Object raw : keys) {
                    if (!(raw instanceof Map<?, ?> key)) {
                        continue;
                    }
                    if (textOf(key.get("left")) == null || textOf(key.get("right")) == null) {
                        errors.add(new Issue(node.id(), "keys", "关联键需要同时指定左表和右表字段"));
                    }
                }
            }
            case "PIVOT" -> {
                requireText(config, "pivotColumn", node, errors, "行转列需要指定「转为列」的字段");
                requireText(config, "valueColumn", node, errors, "行转列需要指定「取值」字段");
                if (asList(config.get("values")).isEmpty()) {
                    errors.add(new Issue(node.id(), "values", "行转列至少需要一个列值"));
                }
                String aggregate = textOf(config.get("aggregate"));
                if (aggregate != null
                        && !Set.of("SUM", "COUNT", "AVG", "MIN", "MAX").contains(aggregate.toUpperCase(Locale.ROOT))) {
                    errors.add(new Issue(node.id(), "aggregate", "不支持的聚合函数：" + aggregate));
                }
            }
            case "UNPIVOT" -> {
                long valid = asList(config.get("columns")).stream()
                        .filter(item -> textOf(item) != null).count();
                if (valid == 0) {
                    errors.add(new Issue(node.id(), "columns", "列转行至少需要一个字段"));
                }
            }
            case "SQL" -> {
                String sql = textOf(config.get("sql"));
                if (sql == null) {
                    errors.add(new Issue(node.id(), "sql", "请填写 SQL"));
                } else if (!sql.contains("{{input}}")) {
                    errors.add(new Issue(node.id(), "sql", "SQL 需要用 {{input}} 引用上游临时表"));
                } else if (sql.contains(";")) {
                    errors.add(new Issue(node.id(), "sql", "SQL 算子只能包含一条查询语句"));
                } else if (!DevNodeReader.isReadOnlyQuery(sql.replace("{{input}}", "t"))) {
                    errors.add(new Issue(node.id(), "sql", "SQL 算子只支持 SELECT 或 WITH 查询"));
                }
            }
            default -> {
                // PROJECT / SORT / DEDUP：空配置有明确兜底（透传 / 上游顺序 / 全行去重），不拦
            }
        }
    }

    private static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** 入边数量要求：输入节点 0，输出节点 1，双输入算子 2，其余 1。 */
    private static int requiredInputs(DevNode node) {
        if (TYPE_DB_INPUT.equals(node.type()) || TYPE_SQL_INPUT.equals(node.type())
                || TYPE_DATASET_INPUT.equals(node.type())) {
            return 0;
        }
        if (TYPE_DB_OUTPUT.equals(node.type()) || TYPE_DATASET_OUTPUT.equals(node.type())) {
            return 1;
        }
        Object operator = node.configOrEmpty().get("operator");
        return DevOperatorSql.isTwoInput(operator == null ? null : String.valueOf(operator)) ? 2 : 1;
    }

    private void requireText(Map<String, Object> config, String field, DevNode node,
                             List<Issue> errors, String message) {
        Object value = config.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            errors.add(new Issue(node.id(), field, message));
        }
    }

    private void requireNumber(Map<String, Object> config, String field, DevNode node,
                               List<Issue> errors, String message) {
        Object value = config.get(field);
        boolean present = value instanceof Number
                || (value instanceof String text && !text.isBlank());
        if (!present) {
            errors.add(new Issue(node.id(), field, message));
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }
}
