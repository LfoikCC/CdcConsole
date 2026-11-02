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

    /** 一个上游的物理表与列。双输入算子按连线顺序拿到左、右两个。 */
    public record Input(String table, List<ColumnMeta> columns) {
    }

    /** 需要两个上游的算子；其余算子（含输出节点）仍然只接受一条入边。 */
    public static final Set<String> TWO_INPUT_OPERATORS = Set.of("UNION", "JOIN");

    public static boolean isTwoInput(String operator) {
        return operator != null && TWO_INPUT_OPERATORS.contains(operator.toUpperCase(Locale.ROOT));
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
        return plan(operator, config, new Input(upstream, upstreamColumns), null);
    }

    public Plan plan(String operator, Map<String, Object> config, Input left, Input right) {
        if (operator == null) {
            throw new IllegalArgumentException("算子节点缺少 operator 配置");
        }
        if (left == null) {
            throw new IllegalArgumentException("算子节点没有上游数据");
        }
        String normalized = operator.toUpperCase(Locale.ROOT);
        Plan plan = switch (normalized) {
            case "PROJECT" -> project(config, left.table(), left.columns());
            case "FILTER" -> filter(config, left.table(), left.columns());
            case "SORT" -> sort(config, left.table(), left.columns());
            case "DEDUP" -> dedup(config, left.table(), left.columns());
            case "AGGREGATE" -> aggregate(config, left.table(), left.columns());
            case "CALCULATE" -> calculate(config, left.table(), left.columns());
            case "REPLACE_VALUE" -> replaceValue(config, left.table(), left.columns());
            case "REPLACE_NULL" -> replaceNull(config, left.table(), left.columns());
            case "MERGE_COLUMNS" -> mergeColumns(config, left.table(), left.columns());
            case "UNION" -> union(config, left, right);
            case "JOIN" -> join(config, left, right);
            case "PIVOT" -> pivot(config, left);
            case "UNPIVOT" -> unpivot(config, left);
            // SQL 算子的输出列要执行后从结果集元数据读，不走这里的静态列推导
            case "SQL" -> throw new IllegalArgumentException("SQL 算子由执行器直接执行，不能静态推导列");
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
        projection.add("ROW_NUMBER() OVER (ORDER BY x.\"ORD\") AS \"ORD\"");
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

    // ---------------------------------------------------------------- UNION

    /** 行拼接：两侧列数必须一致，按位置对齐；输出列名与类型沿用左侧。 */
    private Plan union(Map<String, Object> config, Input left, Input right) {
        if (right == null) {
            throw new IllegalArgumentException("行拼接需要两个上游");
        }
        List<ColumnMeta> l = left.columns();
        List<ColumnMeta> r = right.columns();
        if (l.size() != r.size()) {
            throw new IllegalArgumentException("行拼接要求两侧列数一致：左 " + l.size() + " 列，右 " + r.size() + " 列");
        }
        if (l.isEmpty()) {
            throw new IllegalArgumentException("行拼接的上游没有可用列");
        }
        boolean distinct = "DISTINCT".equalsIgnoreCase(text(config.get("mode")));
        List<String> physical = new ArrayList<>();
        for (int i = 1; i <= l.size(); i++) {
            physical.add("\"C" + i + "\"");
        }
        String list = String.join(", ", physical);
        String leftRows = "SELECT 1 AS \"SRC\", \"ORD\", " + list + " FROM " + left.table();
        String rightRows = "SELECT 2 AS \"SRC\", \"ORD\", " + list + " FROM " + right.table();
        String inner = leftRows + " UNION ALL " + rightRows;
        String sql;
        if (distinct) {
            // 按最早出现的 ORD 排序：DISTINCT 之后仍保持「先出现的在前」
            sql = "SELECT ROW_NUMBER() OVER (ORDER BY MIN(u.\"ORD\")) AS \"ORD\", "
                    + qualifiedColumns("u", l.size())
                    + " FROM (" + inner + ") u GROUP BY " + qualifiedColumns("u", l.size());
        } else {
            // 先按来源、再按各自 ORD：左侧整体在前，右侧整体在后，而不是按 ORD 交错
            sql = "SELECT ROW_NUMBER() OVER (ORDER BY u.\"SRC\", u.\"ORD\") AS \"ORD\", "
                    + qualifiedColumns("u", l.size())
                    + " FROM (" + inner + ") u";
        }
        return new Plan(passThrough(l), sql, List.of());
    }

    // ----------------------------------------------------------------- JOIN

    /** 关联数据：左表全部列 + 右表非关联键列；右表重名列加 {@code _右} 后缀。 */
    private Plan join(Map<String, Object> config, Input left, Input right) {
        if (right == null) {
            throw new IllegalArgumentException("关联数据需要两个上游");
        }
        String type = text(config.get("joinType"));
        String joinType = switch (type == null ? "INNER" : type.toUpperCase(Locale.ROOT)) {
            case "INNER" -> "INNER JOIN";
            case "LEFT" -> "LEFT JOIN";
            case "RIGHT" -> "RIGHT JOIN";
            default -> throw new IllegalArgumentException("不支持的关联类型：" + type);
        };
        List<Object> keys = DevGraphValidator.asList(config.get("keys"));
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("关联数据至少需要一个关联键");
        }

        List<String> on = new ArrayList<>();
        Set<String> rightKeyLabels = new LinkedHashSet<>();
        for (Object raw : keys) {
            if (!(raw instanceof Map<?, ?> key)) {
                continue;
            }
            String leftLabel = text(key.get("left"));
            String rightLabel = text(key.get("right"));
            if (leftLabel == null || rightLabel == null) {
                throw new IllegalArgumentException("关联键需要同时指定左表和右表字段");
            }
            ColumnMeta leftColumn = require(left.columns(), leftLabel);
            ColumnMeta rightColumn = require(right.columns(), rightLabel);
            on.add("l.\"" + leftColumn.physical() + "\" = r.\"" + rightColumn.physical() + "\"");
            rightKeyLabels.add(rightLabel);
        }
        if (on.isEmpty()) {
            throw new IllegalArgumentException("关联数据没有有效的关联键");
        }

        List<ColumnMeta> columns = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<String> expressions = new ArrayList<>();
        for (ColumnMeta column : left.columns()) {
            columns.add(new ColumnMeta(columns.size() + 1, column.label(), column.h2Type()));
            used.add(column.label());
            // 显式别名成 C1..Cn：左右表的物理列名会重名，派生表里必须唯一
            expressions.add("l.\"" + column.physical() + "\" AS \"C" + columns.size() + "\"");
        }
        for (ColumnMeta column : right.columns()) {
            if (rightKeyLabels.contains(column.label())) {
                continue;
            }
            String label = column.label();
            while (used.contains(label)) {
                label = label + "_右";
            }
            used.add(label);
            columns.add(new ColumnMeta(columns.size() + 1, label, column.h2Type()));
            expressions.add("r.\"" + column.physical() + "\" AS \"C" + columns.size() + "\"");
        }

        // 外连接时一侧的 ORD 会是 NULL，取另一侧保证顺序稳定
        String ord = "ROW_NUMBER() OVER (ORDER BY COALESCE(l.\"ORD\", r.\"ORD\"))";
        String sql = "SELECT " + ord + " AS \"ORD\""
                + (expressions.isEmpty() ? "" : ", " + String.join(", ", expressions))
                + " FROM " + left.table() + " l " + joinType + " " + right.table() + " r"
                + " ON " + String.join(" AND ", on);
        return new Plan(columns, sql, List.of());
    }

    // ---------------------------------------------------------------- PIVOT

    /**
     * 行转列。
     *
     * <p>列值必须由用户显式给出：输出列在生成 SQL 时就要确定，而 {@code plan()} 不访问数据库，
     * 没法先查 DISTINCT 值。显式列表也顺带保证了同一份配置每次产出同样的列。
     */
    private Plan pivot(Map<String, Object> config, Input left) {
        List<ColumnMeta> up = left.columns();
        List<ColumnMeta> groups = new ArrayList<>();
        List<String> groupRefs = new ArrayList<>();
        for (Object raw : DevGraphValidator.asList(config.get("groupBy"))) {
            String label = text(raw);
            if (label == null) {
                continue;
            }
            ColumnMeta column = require(up, label);
            groups.add(column);
            groupRefs.add(ref(column));
        }
        String pivotLabel = text(config.get("pivotColumn"));
        String valueLabel = text(config.get("valueColumn"));
        if (pivotLabel == null) {
            throw new IllegalArgumentException("行转列需要指定「转为列」的字段");
        }
        if (valueLabel == null) {
            throw new IllegalArgumentException("行转列需要指定「取值」字段");
        }
        ColumnMeta pivotColumn = require(up, pivotLabel);
        ColumnMeta valueColumn = require(up, valueLabel);

        List<String> values = new ArrayList<>();
        for (Object raw : DevGraphValidator.asList(config.get("values"))) {
            String value = text(raw);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("行转列至少需要一个列值");
        }

        String function = text(config.get("aggregate"));
        String upper = function == null ? "SUM" : function.toUpperCase(Locale.ROOT);
        if (!Set.of("SUM", "COUNT", "AVG", "MIN", "MAX").contains(upper)) {
            throw new IllegalArgumentException("不支持的聚合函数：" + function);
        }
        if ("SUM".equals(upper) || "AVG".equals(upper)) {
            requireNumeric(valueColumn, upper);
        }

        List<ColumnMeta> columns = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (ColumnMeta column : groups) {
            columns.add(new ColumnMeta(columns.size() + 1, column.label(), column.h2Type()));
            expressions.add(ref(column));
        }
        for (String value : values) {
            if (groups.stream().anyMatch(column -> column.label().equals(value))) {
                throw new IllegalArgumentException("列值 " + value + " 与分组字段重名，无法作为输出列");
            }
            expressions.add(upper + "(CASE WHEN " + ref(pivotColumn) + " = ? THEN " + ref(valueColumn) + " END)");
            params.add(value);
            columns.add(new ColumnMeta(columns.size() + 1, value, pivotType(upper, valueColumn)));
        }

        String ord = "ROW_NUMBER() OVER (ORDER BY MIN(up.\"ORD\"))";
        String groupClause = groupRefs.isEmpty() ? null : String.join(", ", groupRefs);
        return new Plan(columns, select(left.table(), ord, expressions, null, groupClause), params);
    }

    private static String pivotType(String function, ColumnMeta value) {
        return switch (function) {
            case "COUNT" -> "BIGINT";
            case "SUM", "AVG" -> "DOUBLE".equalsIgnoreCase(value.h2Type()) ? "DOUBLE" : "DECIMAL(38,10)";
            default -> value.h2Type();
        };
    }

    // -------------------------------------------------------------- UNPIVOT

    /** 列转行：保留其余列，把选中列展开成「字段名 / 值」两列；值统一转 VARCHAR。 */
    private Plan unpivot(Map<String, Object> config, Input left) {
        List<ColumnMeta> up = left.columns();
        List<ColumnMeta> sources = new ArrayList<>();
        Set<String> unpivoted = new LinkedHashSet<>();
        for (Object raw : DevGraphValidator.asList(config.get("columns"))) {
            String label = text(raw);
            if (label == null) {
                continue;
            }
            ColumnMeta column = require(up, label);
            sources.add(column);
            unpivoted.add(label);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("列转行至少需要一个字段");
        }
        String nameLabel = text(config.get("nameColumn"));
        String valueLabel = text(config.get("valueColumn"));
        if (nameLabel == null) {
            nameLabel = "字段";
        }
        if (valueLabel == null) {
            valueLabel = "值";
        }
        if (nameLabel.equals(valueLabel)) {
            throw new IllegalArgumentException("字段名列与值列名不能相同");
        }

        List<ColumnMeta> kept = new ArrayList<>();
        for (ColumnMeta column : up) {
            if (!unpivoted.contains(column.label())) {
                kept.add(column);
            }
        }

        List<String> branches = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            ColumnMeta source = sources.get(i);
            List<String> select = new ArrayList<>();
            select.add("up.\"ORD\" AS \"ORD\"");
            for (ColumnMeta column : kept) {
                select.add(ref(column) + " AS \"" + column.physical() + "\"");
            }
            select.add((i + 1) + " AS \"SEQ\"");
            select.add("CAST(? AS VARCHAR) AS \"CNAME\"");
            params.add(source.label());
            select.add("CAST(" + ref(source) + " AS VARCHAR) AS \"CVAL\"");
            branches.add("SELECT " + String.join(", ", select) + " FROM " + left.table() + " up");
        }

        List<String> outer = new ArrayList<>();
        for (ColumnMeta column : kept) {
            outer.add("u.\"" + column.physical() + "\"");
        }
        outer.add("u.\"CNAME\"");
        outer.add("u.\"CVAL\"");
        String sql = "SELECT ROW_NUMBER() OVER (ORDER BY u.\"ORD\", u.\"SEQ\") AS \"ORD\", "
                + String.join(", ", outer)
                + " FROM (" + String.join(" UNION ALL ", branches) + ") u";

        List<ColumnMeta> columns = passThrough(kept);
        columns.add(new ColumnMeta(columns.size() + 1, nameLabel, "VARCHAR"));
        columns.add(new ColumnMeta(columns.size() + 1, valueLabel, "VARCHAR"));
        return new Plan(columns, sql, params);
    }

    private static String qualifiedColumns(String alias, int count) {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            columns.add(alias + ".\"C" + i + "\"");
        }
        return String.join(", ", columns);
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
        StringBuilder sql = new StringBuilder("SELECT ").append(ordExpression).append(" AS \"ORD\"");
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
