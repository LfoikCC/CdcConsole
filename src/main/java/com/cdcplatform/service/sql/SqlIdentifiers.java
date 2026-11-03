package com.cdcplatform.service.sql;

import com.cdcplatform.model.Types.DatabaseType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL 标识符与只读查询的公共工具。
 *
 * <p>数据开发（读源表、拼算子 SQL）和数据分析（图表聚合）必须用**同一套**引用规则，
 * 否则会出现「数据集预览正常、图表报语法错」这种极难排查的偏差——库名带连字符
 * （{@code CDC-SOURCE}）时最容易撞上。
 *
 * <p>这里只做纯字符串处理，不碰数据库，方便单测。
 */
public final class SqlIdentifiers {

    private SqlIdentifiers() {
    }

    /**
     * 标识符里不允许出现的字符：引号、分号与换行。
     *
     * <p>不能只放行「字母数字下划线」——真实的库表名经常带连字符（{@code CDC-SOURCE}）或中文，
     * 那种白名单会把正常配置挡在门外。这里改为「禁止会破坏引用语法的字符」，其余一律加引号处理。
     */
    private static final Pattern UNSAFE_IDENTIFIER = Pattern.compile("[`\\[\\]\"';\\r\\n]");
    private static final Pattern LEADING_KEYWORD = Pattern.compile("(?is)^(select|with)\\b");

    /** FROM / JOIN 后面紧跟的「库.表」限定名。已带引号的写法不会被匹配。 */
    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "(?i)\\b(from|join)\\s+([A-Za-z0-9_$-]+(?:\\.[A-Za-z0-9_$-]+)*)");
    /** SQL 字符串字面量：改写限定名之前先挖掉，免得把 'from a-b.c' 这种内容也改了。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");

    /** 按方言给标识符加引号。 */
    public static String quote(DatabaseType type, String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 128
                || UNSAFE_IDENTIFIER.matcher(identifier).find()) {
            throw new IllegalArgumentException("非法标识符：" + identifier);
        }
        return type == DatabaseType.SQLSERVER ? "[" + identifier + "]" : "`" + identifier + "`";
    }

    /** H2（应用自己的库）用双引号；规则与 {@link #quote} 一致。 */
    public static String h2Quote(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 128
                || UNSAFE_IDENTIFIER.matcher(identifier).find()) {
            throw new IllegalArgumentException("非法标识符：" + identifier);
        }
        return "\"" + identifier + "\"";
    }

    public static String qualified(DatabaseType type, String database, String table) {
        String quotedTable = quote(type, table);
        if (database == null) {
            return quotedTable;
        }
        return quote(type, database) + "." + quotedTable;
    }

    /**
     * 给自定义 SQL 里带连字符的限定名补上引号。
     *
     * <p>{@code FROM CDC-SOURCE.Orders} 这种写法 MySQL 会当成减法直接报语法错，
     * 但库名带连字符是常见约定（CDC-SOURCE / CDC-TARGET），不该逼用户自己记得加反引号。
     * 只动 FROM / JOIN 后面的标识符链，且只给「含特殊字符」的那一段加引号，
     * 字符串字面量先摘出去再还原，其余 SQL 原样保留。
     */
    public static String quoteQualifiedNames(String sql, DatabaseType type) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        List<String> literals = new ArrayList<>();
        Matcher literalMatcher = STRING_LITERAL.matcher(sql);
        StringBuilder masked = new StringBuilder();
        while (literalMatcher.find()) {
            literals.add(literalMatcher.group());
            literalMatcher.appendReplacement(masked,
                    Matcher.quoteReplacement("'\u0000" + (literals.size() - 1) + "\u0000'"));
        }
        literalMatcher.appendTail(masked);

        Matcher matcher = QUALIFIED_NAME.matcher(masked.toString());
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String quoted = Arrays.stream(matcher.group(2).split("\\."))
                    .map(part -> needsQuote(part) ? quote(type, part) : part)
                    .collect(Collectors.joining("."));
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + " " + quoted));
        }
        matcher.appendTail(out);

        String restored = out.toString();
        for (int i = 0; i < literals.size(); i++) {
            restored = restored.replace("'\u0000" + i + "\u0000'", literals.get(i));
        }
        return restored;
    }

    /** 含字母数字下划线美元符之外的字符（连字符、中文……）就必须加引号。 */
    public static boolean needsQuote(String identifier) {
        for (int i = 0; i < identifier.length(); i++) {
            char ch = identifier.charAt(i);
            boolean safe = Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
            if (!safe) {
                return true;
            }
        }
        return false;
    }

    public static String stripComments(String sql) {
        if (sql == null) {
            return "";
        }
        String withoutBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("(?m)--.*$", " ").trim();
    }

    /** 去掉末尾分号后仍含分号，说明是多条语句。 */
    public static boolean hasMultipleStatements(String sql) {
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.contains(";");
    }

    /** 一条已清洗的 SQL 是否是可执行的只读查询。 */
    public static boolean isReadOnlyQuery(String sql) {
        String cleaned = stripComments(sql);
        return LEADING_KEYWORD.matcher(cleaned).find() && !hasMultipleStatements(cleaned);
    }

    /**
     * 给连接串补上超时。
     *
     * <p>没有超时的 JDBC 连接在主机不可达时会一直等操作系统放弃（动辄数分钟），
     * 而数据流与图表查询都是串行执行的：一个死掉的主机就能把整个模块卡住。
     */
    public static String withTimeouts(String url, DatabaseType type) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("jdbc:mysql:")) {
            return url + "&connectTimeout=10000&socketTimeout=120000";
        }
        if (url.startsWith("jdbc:sqlserver:")) {
            return url + ";loginTimeout=10;socketTimeout=120000";
        }
        return url;
    }
}
