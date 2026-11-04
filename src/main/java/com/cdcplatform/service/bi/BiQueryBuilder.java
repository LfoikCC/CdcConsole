package com.cdcplatform.service.bi;

import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.service.dev.DevOperatorSql;
import com.cdcplatform.service.sql.SqlIdentifiers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把「图表配置」翻译成一条聚合 SQL。
 *
 * <p>纯函数、不碰数据库：单元测试可以直接断言 SQL 与参数，不需要起 Spring。
 * 四种数据集在这里被抹平成一个 {@link Source}：
 * <ul>
 *   <li>TABLE → {@code `db`.`tbl`}，列就是真实列名；</li>
 *   <li>SQL   → {@code ( 用户 SQL ) src}，先过只读检查；</li>
 *   <li>EXCEL / EXTRACT → H2 里的物化表，**物理列名是 C1..Cn**，
 *       真实列名只在展示时用（这是本模块最容易踩的坑）。</li>
 * </ul>
 */
@Service
public class BiQueryBuilder {

    /** 一个可查询的列：{@code label} 给用户看，{@code physical} 拼进 SQL。 */
    public record Column(String label, String physical) {
    }

    /** kind = TABLE | SQL | STORAGE；STORAGE 时 dialect 为 null（走 H2 双引号）。 */
    public record Source(String kind, DatabaseType dialect, String database, String table, String sql,
                         List<Column> columns) {

        public static Source table(DatabaseType dialect, String database, String table, List<Column> columns) {
            return new Source("TABLE", dialect, database, table, null, List.copyOf(columns));
        }

        public static Source sql(DatabaseType dialect, String database, String sql, List<Column> columns) {
            return new Source("SQL", dialect, database, null, sql, List.copyOf(columns));
        }

        public static Source storage(String schema, String table, List<Column> columns) {
            return new Source("STORAGE", null, schema, table, null, List.copyOf(columns));
        }

        boolean isStorage() {
            return "STORAGE".equals(kind);
        }
    }

    /** alias 为空时展示原始列名；用户改过别名就只显示别名（表头、图例会用到）。 */
    public record Dimension(String column, String alias) {

        public Dimension(String column) {
            this(column, null);
        }
    }

    public record Metric(String column, String agg, String alias) {
    }

    public record Filter(String column, String op, Object value) {
    }

    /** custom 是维度值的自定义顺序；为空表示按值本身升/降序。 */
    public record Sort(String by, int index, String dir, List<String> custom) {

        public Sort(String by, int index, String dir) {
            this(by, index, dir, List.of());
        }
    }

    public record CardConfig(List<Dimension> dimensions, List<Metric> metrics, List<Filter> filters,
                             Sort sort, Integer limit) {

        public static CardConfig of(List<Dimension> dimensions, List<Metric> metrics) {
            return new CardConfig(dimensions, metrics, List.of(), null, null);
        }

        /** 从卡片 JSON 解析。缺字段一律当空，不抛异常——半成品配置也要能预览。 */
        public static CardConfig parse(Map<String, Object> raw) {
            Map<String, Object> config = raw == null ? Map.of() : raw;
            List<Dimension> dimensions = new ArrayList<>();
            for (Map<String, Object> item : maps(config.get("dimensions"))) {
                String column = text(item.get("column"));
                if (column != null) {
                    dimensions.add(new Dimension(column, text(item.get("alias"))));
                }
            }
            List<Metric> metrics = new ArrayList<>();
            for (Map<String, Object> item : maps(config.get("metrics"))) {
                String column = text(item.get("column"));
                String agg = text(item.get("agg"));
                if (agg != null) {
                    metrics.add(new Metric(column == null ? "" : column, agg, text(item.get("alias"))));
                }
            }
            List<Filter> filters = new ArrayList<>();
            for (Map<String, Object> item : maps(config.get("filters"))) {
                String column = text(item.get("column"));
                String op = text(item.get("op"));
                if (column != null && op != null) {
                    filters.add(new Filter(column, op, item.get("value")));
                }
            }
            Sort sort = null;
            if (config.get("sort") instanceof Map<?, ?> rawSort) {
                String by = text(rawSort.get("by"));
                String dir = text(rawSort.get("dir"));
                if (by != null && !"none".equalsIgnoreCase(by)) {
                    List<String> custom = new ArrayList<>();
                    if (rawSort.get("custom") instanceof List<?> items) {
                        for (Object item : items) {
                            if (item != null) {
                                custom.add(String.valueOf(item));
                            }
                        }
                    }
                    sort = new Sort(by, number(rawSort.get("index")), dir == null ? "desc" : dir, custom);
                }
            }
            return new CardConfig(dimensions, metrics, filters, sort, number(config.get("limit")));
        }
    }

    /** 生成结果：{@code labels} 是展示用列名，顺序与 SELECT 一致。 */
    public record Plan(String sql, List<Object> params, List<String> labels, int limit) {
    }

    private static final Set<String> OPS = Set.of("=", "<>", "!=", ">", ">=", "<", "<=",
            "LIKE", "NOT LIKE", "IS NULL", "IS NOT NULL", "IN", "NOT IN", "BETWEEN");

    /** 不聚合：维度拖进数值、或用户显式选「无处理」时用。 */
    public static final String NO_AGG = "NONE";
    // 候选值接口一次最多给 200 个，全选也不能被卡住，所以放到 500
    private static final int MAX_IN_VALUES = 500;

    public Plan build(Source source, CardConfig config, int maxRows) {
        if (source == null) {
            throw new IllegalArgumentException("卡片还没有选择数据集");
        }
        CardConfig safe = config == null ? CardConfig.of(List.of(), List.of()) : config;
        if (safe.dimensions().isEmpty() && safe.metrics().isEmpty()) {
            throw new IllegalArgumentException("卡片还没有选择字段");
        }

        List<String> select = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();
        for (int i = 0; i < safe.dimensions().size(); i++) {
            Dimension dimension = safe.dimensions().get(i);
            Column column = requireColumn(source, dimension.column());
            String alias = "c" + i;
            String expression = expr(source, column);
            select.add(expression + " AS " + alias);
            // GROUP BY 必须重复表达式：SQL Server 不接受按别名分组
            groupBy.add(expression);
            labels.add(blank(dimension.alias()) ? column.label() : dimension.alias().trim());
        }
        for (int i = 0; i < safe.metrics().size(); i++) {
            String alias = "m" + i;
            Metric metric = safe.metrics().get(i);
            String function = metric.agg() == null ? "" : metric.agg().trim().toUpperCase(Locale.ROOT);
            String inner = metric.column() == null || metric.column().isBlank()
                    ? null
                    : expr(source, requireColumn(source, metric.column()));
            if (NO_AGG.equals(function)) {
                // 无处理：原样取这一列，不做聚合
                if (inner == null) {
                    throw new IllegalArgumentException("无处理需要选择一个字段");
                }
                select.add(inner + " AS " + alias);
                // 有维度时它就是分组键，否则 SQL 直接报「列不在 GROUP BY 中」
                if (!safe.dimensions().isEmpty()) {
                    groupBy.add(inner);
                }
                labels.add(blank(metric.alias()) ? columnLabel(source, metric.column()) : metric.alias().trim());
                continue;
            }
            if (!DevOperatorSql.AGG_FUNCTIONS.contains(function)) {
                throw new IllegalArgumentException("不支持的聚合方式：" + metric.agg());
            }
            if (inner == null && !"COUNT".equals(function)) {
                throw new IllegalArgumentException(function + " 需要选择一个字段");
            }
            select.add(aggregate(function, inner) + " AS " + alias);
            labels.add(blank(metric.alias()) ? labelOf(source, metric, function, inner == null) : metric.alias().trim());
        }

        List<Object> params = new ArrayList<>();
        String where = whereClause(source, safe.filters(), params);

        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select))
                .append(" FROM ").append(fromClause(source));
        if (where != null) {
            sql.append(" WHERE ").append(where);
        }
        if (!groupBy.isEmpty() && !safe.metrics().isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        }

        String orderBy = orderByClause(safe, source, params);
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        } else if (isSqlServer(source)) {
            // OFFSET/FETCH 强制要求 ORDER BY，没配排序时补一个稳定的默认排序
            sql.append(" ORDER BY ").append(safe.dimensions().isEmpty() ? "m0" : "c0");
        }

        int limit = Math.max(1, Math.min(safe.limit() == null ? 200 : safe.limit(), Math.max(1, maxRows)));
        // 多取一行：执行层靠「还有没有下一行」判断结果是否被截断，SQL 侧直接写 n 就永远判不出来
        int fetch = limit + 1;
        sql.append(isSqlServer(source)
                ? " OFFSET 0 ROWS FETCH NEXT " + fetch + " ROWS ONLY"
                : " LIMIT " + fetch);

        return new Plan(sql.toString(), params, labels, limit);
    }

    /** 数据集总行数：数据集元数据里对库表一直是 0，只能在编辑器里按需算一次。 */
    public Plan countRows(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("卡片还没有选择数据集");
        }
        return new Plan("SELECT COUNT(*) AS v FROM " + fromClause(source), List.of(), List.of("行数"), 1);
    }

    /** 取某一列的取值清单（筛选的可选项、自定义排序的候选值都用它）。 */
    public Plan distinctValues(Source source, String column, int limit) {
        if (source == null) {
            throw new IllegalArgumentException("卡片还没有选择数据集");
        }
        Column col = requireColumn(source, column);
        String expression = expr(source, col);
        int capped = Math.max(1, Math.min(limit, 1000));
        int fetch = capped + 1;
        String sql = "SELECT DISTINCT " + expression + " AS v FROM " + fromClause(source)
                + " WHERE " + expression + " IS NOT NULL"
                + (isSqlServer(source)
                ? " ORDER BY v OFFSET 0 ROWS FETCH NEXT " + fetch + " ROWS ONLY"
                : " ORDER BY 1 LIMIT " + fetch);
        return new Plan(sql, List.of(), List.of(col.label()), capped);
    }

    // ------------------------------------------------------------- 片段拼装

    private String fromClause(Source source) {
        if (source.isStorage()) {
            return SqlIdentifiers.h2Quote(source.database()) + "." + SqlIdentifiers.h2Quote(source.table());
        }
        if ("SQL".equals(source.kind())) {
            String cleaned = SqlIdentifiers.stripComments(source.sql());
            if (!SqlIdentifiers.isReadOnlyQuery(cleaned)) {
                throw new IllegalArgumentException("数据集 SQL 只支持单条 SELECT 或 WITH 查询");
            }
            return "(" + SqlIdentifiers.quoteQualifiedNames(cleaned, source.dialect()) + ") src";
        }
        return SqlIdentifiers.qualified(source.dialect(), source.database(), source.table());
    }

    private String whereClause(Source source, List<Filter> filters, List<Object> params) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Filter filter : filters) {
            String op = filter.op() == null ? "" : filter.op().trim().toUpperCase(Locale.ROOT);
            if (!OPS.contains(op)) {
                throw new IllegalArgumentException("不支持的筛选操作符：" + filter.op());
            }
            String column = expr(source, requireColumn(source, filter.column()));
            switch (op) {
                case "IS NULL", "IS NOT NULL" -> parts.add(column + " " + op);
                case "IN", "NOT IN" -> {
                    List<Object> values = valuesOf(filter.value());
                    // 还没勾选任何值时不参与过滤，和空值的条件筛选保持一致
                    if (values.isEmpty()) {
                        continue;
                    }
                    if (values.size() > MAX_IN_VALUES) {
                        throw new IllegalArgumentException("筛选条件 " + op + " 最多 " + MAX_IN_VALUES + " 个值");
                    }
                    String list = column + " " + op + " ("
                            + String.join(", ", values.stream().map(v -> "?").toList()) + ")";
                    if ("NOT IN".equals(op)) {
                        // 排除时空值不能被顺手干掉：SQL 里 NULL NOT IN (...) 结果是 UNKNOWN，
                        // 整行会被过滤掉，用户看到的就是「勾了排除数据全没了」
                        parts.add("(" + list + " OR " + column + " IS NULL)");
                    } else {
                        parts.add(list);
                    }
                    params.addAll(values);
                }
                case "BETWEEN" -> {
                    List<Object> range = valuesOf(filter.value());
                    Object from = range.isEmpty() ? null : range.get(0);
                    Object to = range.size() < 2 ? null : range.get(1);
                    if (from == null && to == null) {
                        continue;
                    }
                    if (from != null && to != null) {
                        parts.add(column + " BETWEEN ? AND ?");
                        params.add(from);
                        params.add(to);
                    } else if (from != null) {
                        parts.add(column + " >= ?");
                        params.add(from);
                    } else {
                        parts.add(column + " <= ?");
                        params.add(to);
                    }
                }
                default -> {
                    // 条件还没填完（刚拖进来、值还空着）就不参与过滤，
                    // 否则卡片会在拖入的瞬间变成空表，看起来像坏了
                    if (filter.value() == null || (filter.value() instanceof String text && text.isBlank())) {
                        continue;
                    }
                    parts.add(column + ("!=".equals(op) ? " <> " : " " + op + " ") + "?");
                    params.add(filter.value());
                }
            }
        }
        // 全部条件都没填完时不要拼出空的 WHERE
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    private String orderByClause(CardConfig config, Source source, List<Object> params) {
        Sort sort = config.sort();
        if (sort == null) {
            return null;
        }
        String alias;
        if ("metric".equalsIgnoreCase(sort.by())) {
            if (sort.index() < 0 || sort.index() >= config.metrics().size()) {
                throw new IllegalArgumentException("排序的度量序号越界：" + sort.index());
            }
            alias = "m" + sort.index();
        } else if ("dimension".equalsIgnoreCase(sort.by())) {
            if (sort.index() < 0 || sort.index() >= config.dimensions().size()) {
                throw new IllegalArgumentException("排序的维度序号越界：" + sort.index());
            }
            alias = "c" + sort.index();
        } else {
            return null;
        }
        String dir = "asc".equalsIgnoreCase(sort.dir()) ? "ASC" : "DESC";
        // 自定义排序：按用户给的取值顺序排，没列到的值排在最后
        if ("dimension".equalsIgnoreCase(sort.by()) && sort.custom() != null && !sort.custom().isEmpty()) {
            Dimension dimension = config.dimensions().get(sort.index());
            String expression = expr(source, requireColumn(source, dimension.column()));
            StringBuilder cases = new StringBuilder("CASE ").append(expression);
            for (int i = 0; i < sort.custom().size(); i++) {
                cases.append(" WHEN ? THEN ").append(i);
                params.add(sort.custom().get(i));
            }
            cases.append(" ELSE ").append(sort.custom().size()).append(" END");
            return cases + " " + dir;
        }
        return alias + " " + dir;
    }

    private static String aggregate(String function, String inner) {
        if (inner == null) {
            return function + "(*)";
        }
        return "COUNT_DISTINCT".equals(function) ? "COUNT(DISTINCT " + inner + ")" : function + "(" + inner + ")";
    }

    /** 汇总方式的展示名：图例、表头直接用这一份，和编辑器里的选项保持一致。 */
    private static final Map<String, String> AGG_LABELS = Map.of(
            "SUM", "求和", "COUNT", "计数", "COUNT_DISTINCT", "去重计数",
            "AVG", "平均值", "MIN", "最小值", "MAX", "最大值");

    private static String labelOf(Source source, Metric metric, String function, boolean countAll) {
        if (countAll) {
            return "行数";
        }
        return AGG_LABELS.getOrDefault(function, function) + "(" + columnLabel(source, metric.column()) + ")";
    }

    private static String columnLabel(Source source, String name) {
        return source.columns().stream()
                .filter(item -> item.label().equals(name) || item.physical().equals(name))
                .map(Column::label).findFirst().orElse(name);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String expr(Source source, Column column) {
        return source.isStorage()
                ? SqlIdentifiers.h2Quote(column.physical())
                : SqlIdentifiers.quote(source.dialect(), column.physical());
    }

    private static Column requireColumn(Source source, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("字段不能为空");
        }
        return source.columns().stream()
                .filter(item -> item.label().equals(name) || item.physical().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("数据集里没有字段：" + name));
    }

    private static boolean isSqlServer(Source source) {
        return source.dialect() == DatabaseType.SQLSERVER;
    }

    // --------------------------------------------------------------- 小工具

    private static List<Map<String, Object>> maps(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    result.add(typed);
                }
            }
        }
        return result;
    }

    private static List<Object> valuesOf(Object raw) {
        Set<Object> values = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            values.addAll(list);
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split(",")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        return new ArrayList<>(values);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
