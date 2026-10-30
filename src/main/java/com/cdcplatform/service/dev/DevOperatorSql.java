package com.cdcplatform.service.dev;

import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 四种基础算子的 SQL 生成。
 *
 * <p>约定：上游表的物理列是 {@code C1..Cn} 加一个 {@code ORD}，所以这里所有表达式都按
 * 「先按列名找到下标，再拼 {@code C<下标>}」的方式构造——用户填的是真实列名，拼进 SQL 的永远是编号。
 * 参数一律走占位符绑定，不做字符串拼接。
 */
@Service
public class DevOperatorSql {

    public record Plan(List<ColumnMeta> columns, String selectSql, List<Object> params) {
    }

    /** 过滤算子可用的比较符；DevGraphValidator 复用同一份，避免两处白名单漂移。 */
    public static final Set<String> COMPARATORS =
            Set.of("=", "<>", "!=", ">", ">=", "<", "<=", "LIKE", "NOT LIKE", "IS NULL", "IS NOT NULL", "IN", "BETWEEN");

    /** 计算列可选的输出类型。前端下拉与 DevGraphValidator 共用同一份，必须逐字一致。 */
    public static final Set<String> CAST_TYPES = Set.of(
            "INTEGER", "BIGINT", "DECIMAL(38,10)", "DOUBLE", "VARCHAR", "DATE", "TIME", "TIMESTAMP", "BOOLEAN");

    /** 分组聚合支持的函数；COUNT 的 column 允许为空，表示 COUNT(*)。 */
    private static final Set<String> AGG_FUNCTIONS = Set.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX");

    /** 原有四个算子：它们的输出列名沿用上游，不做唯一性检查（避免历史配置被拒）。 */
    private static final Set<String> LEGACY_OPERATORS = Set.of("PROJECT", "FILTER", "SORT", "DEDUP");

    public Plan plan(String operator, Map<String, Object> config, String upstream, List<ColumnMeta> upstreamColumns) {
        if (operator == null) {
            throw new IllegalArgumentException("算子节点缺少 operator 配置");
        }
        String normalized = operator.toUpperCase(Locale.ROOT);
        Plan plan = switch (normalized) {
            case "PROJECT" -> project(config, upstream, upstreamColumns);
            case "FILTER" -> filter(config, upstream, upstreamColumns);
            case "SORT" -> sort(config, upstream, upstreamColumns);
            case "DEDUP" -> dedup(config, upstream, upstreamColumns);
            case "AGGREGATE" -> aggregate(config, upstream, upstreamColumns);
            case "CALCULATE" -> calculate(config, upstream, upstreamColumns);
            case "REPLACE_VALUE" -> replaceValue(config, upstream, upstreamColumns);
            case "REPLACE_NULL" -> replaceNull(config, upstream, upstreamColumns);
            case "MERGE_COLUMNS" -> mergeColumns(config, upstream, upstreamColumns);
            default -> throw new IllegalArgumentException("不支持的算子：" + operator);
        };
        if (!LEGACY_OPERATORS.contains(normalized)) {
            requireUniqueLabels(plan.columns(), normalized);
        }
        return plan;
    }

    // ------------------------------------------------------------- PROJECT

    private Plan project(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<Object> items = DevGraphValidator.asList(config.get("items"));
        List<ColumnMeta> columns = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (items.isEmpty()) {
            // 没配置就原样透传，避免「加了个算子但什么都没改」变成空表
            for (ColumnMeta column : up) {
                columns.add(new ColumnMeta(columns.size() + 1, column.label(), column.h2Type()));
                expressions.add("up.\"" + column.physical() + "\"");
            }
        } else {
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> item)) {
                    continue;
                }
                String source = text(item.get("column"));
                if (source == null) {
                    continue;
                }
                ColumnMeta column = require(up, source);
                String alias = text(item.get("alias"));
                String cast = text(item.get("type"));
                String label = alias != null ? alias : column.label();
                String h2Type = column.h2Type();
                String expression = "up.\"" + column.physical() + "\"";
                if (cast != null) {
                    String normalized = cast.toUpperCase(Locale.ROOT);
                    if (!CAST_TYPES.contains(normalized)) {
                        throw new IllegalArgumentException("不支持的转换类型：" + cast);
                    }
                    expression = "CAST(" + expression + " AS " + normalized + ")";
                    h2Type = DevTempTableStore.h2TypeOf(switch (normalized) {
                        case "INTEGER" -> java.sql.Types.INTEGER;
                        case "BIGINT" -> java.sql.Types.BIGINT;
                        case "DECIMAL(38,10)" -> java.sql.Types.DECIMAL;
                        case "DOUBLE" -> java.sql.Types.DOUBLE;
                        case "DATE" -> java.sql.Types.DATE;
                        case "TIME" -> java.sql.Types.TIME;
                        case "TIMESTAMP" -> java.sql.Types.TIMESTAMP;
                        case "BOOLEAN" -> java.sql.Types.BOOLEAN;
                        default -> java.sql.Types.VARCHAR;
                    });
                }
                columns.add(new ColumnMeta(columns.size() + 1, label, h2Type));
                expressions.add(expression);
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("字段算子没有可输出的列");
            }
        }
        return new Plan(columns, select(upstream, "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")", expressions, null),
                params);
    }

    // -------------------------------------------------------------- FILTER

    private Plan filter(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<Object> conditions = DevGraphValidator.asList(config.get("conditions"));
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("过滤算子至少需要一个条件");
        }
        List<ColumnMeta> columns = passThrough(up);
        List<Object> params = new ArrayList<>();
        List<String> parts = new ArrayList<>();

        for (Object raw : conditions) {
            if (!(raw instanceof Map<?, ?> condition)) {
                continue;
            }
            String columnName = text(condition.get("column"));
            String op = text(condition.get("op"));
            if (columnName == null || op == null) {
                continue;
            }
            String normalizedOp = op.toUpperCase(Locale.ROOT);
            if (!COMPARATORS.contains(normalizedOp)) {
                throw new IllegalArgumentException("不支持的比较操作符：" + op);
            }
            ColumnMeta column = require(up, columnName);
            String ref = "up.\"" + column.physical() + "\"";
            Object value = condition.get("value");

            switch (normalizedOp) {
                case "IS NULL", "IS NOT NULL" -> parts.add(ref + " " + normalizedOp);
                case "IN" -> {
                    List<String> values = splitValues(value);
                    if (values.isEmpty()) {
                        throw new IllegalArgumentException("列 " + columnName + " 的 IN 条件没有值");
                    }
                    parts.add(ref + " IN (" + String.join(", ", values.stream().map(v -> "?").toList()) + ")");
                    params.addAll(values);
                }
                case "BETWEEN" -> {
                    List<String> values = splitValues(value);
                    if (values.size() != 2) {
                        throw new IllegalArgumentException("列 " + columnName + " 的 BETWEEN 需要两个值");
                    }
                    parts.add(ref + " BETWEEN ? AND ?");
                    params.addAll(values);
                }
                case "!=" -> {
                    parts.add(ref + " <> ?");
                    params.add(stringValue(value));
                }
                default -> {
                    parts.add(ref + " " + normalizedOp + " ?");
                    params.add(stringValue(value));
                }
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("过滤算子没有有效条件");
        }
        String join = "OR".equalsIgnoreCase(text(config.get("join"))) ? " OR " : " AND ";
        String where = String.join(join, parts);
        return new Plan(columns,
                select(upstream, "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")", passThroughExpressions(up), where),
                params);
    }

    // ---------------------------------------------------------------- SORT

    private Plan sort(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<Object> keys = DevGraphValidator.asList(config.get("keys"));
        List<String> orderBy = new ArrayList<>();
        for (Object raw : keys) {
            if (!(raw instanceof Map<?, ?> key)) {
                continue;
            }
            String columnName = text(key.get("column"));
            if (columnName == null) {
                continue;
            }
            ColumnMeta column = require(up, columnName);
            String direction = "DESC".equalsIgnoreCase(text(key.get("direction"))) ? "DESC" : "ASC";
            orderBy.add("up.\"" + column.physical() + "\" " + direction);
        }
        // 排序键为空时退回上游顺序，而不是报错——用户可能刚拖进来还没配
        String ord = orderBy.isEmpty()
                ? "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")"
                : "ROW_NUMBER() OVER (ORDER BY " + String.join(", ", orderBy) + ")";
        return new Plan(passThrough(up), select(upstream, ord, passThroughExpressions(up), null), List.of());
    }

    // --------------------------------------------------------------- DEDUP

    private Plan dedup(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<Object> keys = DevGraphValidator.asList(config.get("keys"));
        List<String> partition = new ArrayList<>();
        for (Object raw : keys) {
            String columnName = text(raw);
            if (columnName != null) {
                partition.add("\"" + require(up, columnName).physical() + "\"");
            }
        }
        if (partition.isEmpty()) {
            // 全行去重：按所有列分区
            for (ColumnMeta column : up) {
                partition.add("\"" + column.physical() + "\"");
            }
        }
        if (partition.isEmpty()) {
            throw new IllegalArgumentException("去重算子没有可用的列");
        }

        List<ColumnMeta> columns = passThrough(up);
        List<String> projection = new ArrayList<>();
        projection.add("ROW_NUMBER() OVER (ORDER BY x.\"ORD\")");
        for (ColumnMeta column : columns) {
            projection.add("x.\"" + column.physical() + "\"");
        }
        String inner = "SELECT *, ROW_NUMBER() OVER (PARTITION BY " + String.join(", ", partition)
                + " ORDER BY \"ORD\") AS \"RN\" FROM " + upstream;
        String sql = "SELECT " + String.join(", ", projection) + " FROM (" + inner + ") x WHERE x.\"RN\" = 1";
        return new Plan(columns, sql, List.of());
    }

    // ----------------------------------------------------------- AGGREGATE

    private Plan aggregate(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<ColumnMeta> columns = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();

        for (Object raw : DevGraphValidator.asList(config.get("groupBy"))) {
            String label = text(raw);
            if (label == null) {
                continue;
            }
            ColumnMeta column = require(up, label);
            String ref = ref(column);
            groupBy.add(ref);
            expressions.add(ref);
            columns.add(new ColumnMeta(columns.size() + 1, column.label(), column.h2Type()));
        }

        List<Object> aggregates = DevGraphValidator.asList(config.get("aggregates"));
        if (aggregates.isEmpty()) {
            throw new IllegalArgumentException("分组聚合至少需要一个聚合项");
        }

        for (Object raw : aggregates) {
            if (!(raw instanceof Map<?, ?> item)) {
                continue;
            }
            String function = text(item.get("function"));
            if (function == null) {
                throw new IllegalArgumentException("聚合项缺少函数");
            }
            String upper = function.toUpperCase(Locale.ROOT);
            if (!AGG_FUNCTIONS.contains(upper)) {
                throw new IllegalArgumentException("不支持的聚合函数：" + function);
            }
            String alias = text(item.get("alias"));
            if (alias == null) {
                throw new IllegalArgumentException("聚合项缺少输出列名");
            }
            String columnLabel = text(item.get("column"));

            String expression;
            String h2Type;
            if ("COUNT".equals(upper) && columnLabel == null) {
                expression = "COUNT(*)";
                h2Type = "BIGINT";
            } else {
                if (columnLabel == null) {
                    throw new IllegalArgumentException(upper + " 需要指定字段");
                }
                ColumnMeta column = require(up, columnLabel);
                String ref = ref(column);
                switch (upper) {
                    case "COUNT" -> {
                        expression = "COUNT(" + ref + ")";
                        h2Type = "BIGINT";
                    }
                    case "COUNT_DISTINCT" -> {
                        expression = "COUNT(DISTINCT " + ref + ")";
                        h2Type = "BIGINT";
                    }
                    case "SUM", "AVG" -> {
                        requireNumeric(column, upper);
                        expression = upper + "(" + ref + ")";
                        // SUM(BIGINT) 在 H2 返回 NUMERIC，声明成 BIGINT 会溢出，统一落 DECIMAL
                        h2Type = "DOUBLE".equalsIgnoreCase(column.h2Type()) ? "DOUBLE" : "DECIMAL(38,10)";
                    }
                    case "MIN", "MAX" -> {
                        expression = upper + "(" + ref + ")";
                        h2Type = column.h2Type();
                    }
                    default -> throw new IllegalArgumentException("不支持的聚合函数：" + function);
                }
            }
            expressions.add(expression);
            columns.add(new ColumnMeta(columns.size() + 1, alias, h2Type));
        }

        // ORD 用组内最早出现的上游行号，保证同样的输入每次得到同样的顺序
        String ord = "ROW_NUMBER() OVER (ORDER BY MIN(up.\"ORD\"))";
        String groupClause = groupBy.isEmpty() ? null : String.join(", ", groupBy);
        return new Plan(columns, select(upstream, ord, expressions, null, groupClause), List.of());
    }

    private static void requireNumeric(ColumnMeta column, String function) {
        String type = column.h2Type() == null ? "" : column.h2Type().toUpperCase(Locale.ROOT);
        boolean numeric = type.startsWith("INTEGER") || type.startsWith("BIGINT") || type.startsWith("DECIMAL")
                || type.startsWith("NUMERIC") || type.startsWith("DOUBLE");
        if (!numeric) {
            throw new IllegalArgumentException(
                    function + " 需要数值列，而 " + column.label() + " 是 " + column.h2Type());
        }
    }

    // ----------------------------------------------------------- CALCULATE

    private Plan calculate(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        String alias = text(config.get("alias"));
        if (alias == null) {
            throw new IllegalArgumentException("请填写新列名");
        }
        String expression = text(config.get("expression"));
        if (expression == null) {
            throw new IllegalArgumentException("请填写计算表达式");
        }
        String type = text(config.get("type"));
        if (type == null) {
            throw new IllegalArgumentException("请选择计算结果类型");
        }
        String normalizedType = type.toUpperCase(Locale.ROOT);
        if (!CAST_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("不支持的计算结果类型：" + type);
        }
        for (ColumnMeta column : up) {
            if (column.label().equals(alias)) {
                throw new IllegalArgumentException("新列名 " + alias + " 与上游列重名");
            }
        }

        String translated = DevExpressionValidator.translate(expression, up);
        List<ColumnMeta> columns = passThrough(up);
        List<String> expressions = passThroughExpressions(up);
        // 统一外层 CAST：DDL 类型与 SELECT 类型一致，避免 INSERT 时隐式转换
        expressions.add("CAST(" + translated + " AS " + normalizedType + ")");
        columns.add(new ColumnMeta(columns.size() + 1, alias, normalizedType));
        return new Plan(columns, select(upstream, "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")", expressions, null),
                List.of());
    }

    // ------------------------------------------------------- REPLACE_VALUE

    private Plan replaceValue(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<Object> rules = DevGraphValidator.asList(config.get("rules"));
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("值替换至少需要一条规则");
        }

        // 按列首次出现顺序分组：SQL 与参数顺序都稳定，便于排查
        Map<String, List<Map<?, ?>>> byColumn = new LinkedHashMap<>();
        for (Object raw : rules) {
            if (!(raw instanceof Map<?, ?> rule)) {
                continue;
            }
            String label = text(rule.get("column"));
            if (label == null) {
                throw new IllegalArgumentException("值替换规则缺少字段");
            }
            if (rule.get("from") == null) {
                throw new IllegalArgumentException(
                        "字段 " + label + " 的原值不能为空（NULL 请用 Null 值替换算子）");
            }
            byColumn.computeIfAbsent(label, key -> new ArrayList<>()).add(rule);
        }
        byColumn.keySet().forEach(label -> require(up, label));

        List<Object> params = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        for (ColumnMeta column : up) {
            String ref = ref(column);
            List<Map<?, ?>> columnRules = byColumn.get(column.label());
            if (columnRules == null) {
                expressions.add(ref);
                continue;
            }
            StringBuilder sql = new StringBuilder("CASE");
            for (Map<?, ?> rule : columnRules) {
                sql.append(" WHEN ").append(ref).append(" = ? THEN ?");
                params.add(stringValue(rule.get("from")));
                params.add(rule.get("to") == null ? null : stringValue(rule.get("to")));
            }
            sql.append(" ELSE ").append(ref).append(" END");
            expressions.add(sql.toString());
        }
        return new Plan(passThrough(up), select(upstream, "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")", expressions, null),
                params);
    }

    // -------------------------------------------------------- REPLACE_NULL

    private Plan replaceNull(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<Object> fills = DevGraphValidator.asList(config.get("fills"));
        if (fills.isEmpty()) {
            throw new IllegalArgumentException("Null 值替换至少需要一个字段");
        }

        Map<String, Object> byColumn = new LinkedHashMap<>();
        for (Object raw : fills) {
            if (!(raw instanceof Map<?, ?> fill)) {
                continue;
            }
            String label = text(fill.get("column"));
            if (label == null) {
                throw new IllegalArgumentException("Null 值替换缺少字段");
            }
            byColumn.put(label, fill.get("value"));
        }
        byColumn.keySet().forEach(label -> require(up, label));

        List<Object> params = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        for (ColumnMeta column : up) {
            String ref = ref(column);
            if (!byColumn.containsKey(column.label())) {
                expressions.add(ref);
                continue;
            }
            // COALESCE 比 CASE 直白，且实测保留列类型
            expressions.add("COALESCE(" + ref + ", ?)");
            params.add(stringValue(byColumn.get(column.label())));
        }
        return new Plan(passThrough(up), select(upstream, "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")", expressions, null),
                params);
    }

    // ------------------------------------------------------- MERGE_COLUMNS

    private Plan mergeColumns(Map<String, Object> config, String upstream, List<ColumnMeta> up) {
        List<String> labels = new ArrayList<>();
        for (Object raw : DevGraphValidator.asList(config.get("columns"))) {
            String label = text(raw);
            if (label != null) {
                labels.add(label);
            }
        }
        if (labels.size() < 2) {
            throw new IllegalArgumentException("合并列至少需要两个字段");
        }
        String alias = text(config.get("alias"));
        if (alias == null) {
            throw new IllegalArgumentException("请填写合并后的列名");
        }
        boolean dropSources = "true".equalsIgnoreCase(String.valueOf(config.get("dropSources")));
        Object separator = config.get("separator");
        String separatorValue = separator == null ? "" : String.valueOf(separator);

        List<ColumnMeta> sources = new ArrayList<>();
        for (String label : labels) {
            sources.add(require(up, label));
        }
        for (ColumnMeta column : up) {
            if (column.label().equals(alias) && !labels.contains(column.label())) {
                throw new IllegalArgumentException("合并后的列名 " + alias + " 与上游列重名");
            }
        }

        // 每个源列显式 COALESCE 成空串：H2 的 CONCAT 吞 NULL 是非标准行为，包一层后语义明确
        List<Object> params = new ArrayList<>();
        String expression = concatPart(sources.get(0));
        for (int i = 1; i < sources.size(); i++) {
            params.add(separatorValue);
            expression = "CONCAT(CONCAT(" + expression + ", ?), " + concatPart(sources.get(i)) + ")";
        }

        List<ColumnMeta> columns = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        for (ColumnMeta column : up) {
            if (dropSources && labels.contains(column.label())) {
                continue;
            }
            columns.add(new ColumnMeta(columns.size() + 1, column.label(), column.h2Type()));
            expressions.add(ref(column));
        }
        columns.add(new ColumnMeta(columns.size() + 1, alias, "VARCHAR"));
        expressions.add(expression);
        return new Plan(columns, select(upstream, "ROW_NUMBER() OVER (ORDER BY up.\"ORD\")", expressions, null),
                params);
    }

    private static String concatPart(ColumnMeta column) {
        return "COALESCE(CAST(" + ref(column) + " AS VARCHAR), '')";
    }

    // ------------------------------------------------------------ helpers

    private static String ref(ColumnMeta column) {
        return "up.\"" + column.physical() + "\"";
    }

    private static void requireUniqueLabels(List<ColumnMeta> columns, String operator) {
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnMeta column : columns) {
            if (!seen.add(column.label())) {
                throw new IllegalArgumentException("算子 " + operator + " 的输出列名「" + column.label()
                        + "」重复；下游按列名取列，重名会取到第一列");
            }
        }
    }

    private static String select(String upstream, String ordExpression, List<String> expressions, String where) {
        return select(upstream, ordExpression, expressions, where, null);
    }

    /** {@code groupBy} 为 null 时不追加 GROUP BY 子句（全局聚合就是这种形态）。 */
    private static String select(String upstream, String ordExpression, List<String> expressions,
                                 String where, String groupBy) {
        StringBuilder sql = new StringBuilder("SELECT ").append(ordExpression);
        for (String expression : expressions) {
            sql.append(", ").append(expression);
        }
        sql.append(" FROM ").append(upstream).append(" up");
        if (where != null) {
            sql.append(" WHERE ").append(where);
        }
        if (groupBy != null && !groupBy.isBlank()) {
            sql.append(" GROUP BY ").append(groupBy);
        }
        // 不写 ORDER BY：行序由 ORD 列承载，下游一律 ORDER BY "ORD" 读取。
        return sql.toString();
    }

    private static List<ColumnMeta> passThrough(List<ColumnMeta> up) {
        List<ColumnMeta> columns = new ArrayList<>();
        for (ColumnMeta column : up) {
            columns.add(new ColumnMeta(columns.size() + 1, column.label(), column.h2Type()));
        }
        return columns;
    }

    private static List<String> passThroughExpressions(List<ColumnMeta> up) {
        List<String> expressions = new ArrayList<>();
        for (ColumnMeta column : up) {
            expressions.add("up.\"" + column.physical() + "\"");
        }
        return expressions;
    }

    private static ColumnMeta require(List<ColumnMeta> columns, String label) {
        for (ColumnMeta column : columns) {
            if (column.label().equals(label)) {
                return column;
            }
        }
        throw new IllegalArgumentException("上游没有列 " + label);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> splitValues(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = text(value);
        if (text == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
