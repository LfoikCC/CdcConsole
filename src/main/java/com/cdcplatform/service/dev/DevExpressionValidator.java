package com.cdcplatform.service.dev;

import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 手写 SQL 表达式（添加计算列）的校验与翻译。
 *
 * <p>表达式最终由 H2 在临时表上求值，但用户输入不能直接拼进 SQL：这里把它拆成 token，
 * 只放行「数字 / 字符串 / 算术与比较运算符 / 上游列名 / 白名单函数 / NULL·TRUE·FALSE」，
 * 并把列名替换成物理编号 {@code up."Cn"}。
 *
 * <p>为什么不用纯正则：列名是任意字符串（中文、空格、点号都合法），必须「先按最长前缀匹配列名，
 * 再对剩下的部分做白名单」，正则做不到这个顺序，既会漏也会误。
 */
public final class DevExpressionValidator {

    /** 允许的 H2 内置函数：纯确定性、无副作用。 */
    public static final Set<String> FUNCTIONS = Set.of(
            "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "TRUNC", "MOD", "POWER", "SQRT", "SIGN",
            "CONCAT", "LENGTH", "CHAR_LENGTH", "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM",
            "SUBSTRING", "REPLACE", "LEFT", "RIGHT", "LPAD", "RPAD", "INSTR", "POSITION",
            "COALESCE", "NULLIF",
            "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND");

    private static final Set<String> CONSTANTS = Set.of("NULL", "TRUE", "FALSE");

    private static final Set<String> TWO_CHAR_OPERATORS = Set.of("<>", "!=", "<=", ">=");
    private static final Set<String> ONE_CHAR_OPERATORS = Set.of("+", "-", "*", "/", "%", "(", ")", ",", "=", "<", ">");

    private static final Pattern COMMENT = Pattern.compile("--|/\\*|\\*/");
    private static final Pattern KEYWORD = Pattern.compile(
            "(?i)\\b(select|from|where|insert|update|delete|drop|alter|create|truncate|union|exec|execute|call|"
                    + "attach|runscript|grant|revoke|merge|into|case|when|then|else|end|and|or|not|is|in|between)\\b");

    private DevExpressionValidator() {
    }

    /**
     * 语法级检查：非空、无分号/注释、无语句关键字、括号配平。
     *
     * <p>不需要上游列，因此可以在「校验」按钮阶段就执行——用户不必等到运行时才发现写错。
     */
    public static void checkSyntax(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        if (expression.indexOf(';') >= 0) {
            throw new IllegalArgumentException("表达式不能包含分号");
        }
        if (COMMENT.matcher(expression).find()) {
            throw new IllegalArgumentException("表达式不能包含 SQL 注释（-- 或 /* */）");
        }
        var keyword = KEYWORD.matcher(expression);
        if (keyword.find()) {
            throw new IllegalArgumentException(
                    "表达式不能包含语句关键字或子查询：" + keyword.group().toUpperCase(Locale.ROOT));
        }
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                int close = expression.indexOf('\'', i + 1);
                if (close < 0) {
                    throw new IllegalArgumentException("表达式第 " + (i + 1) + " 个字符处的字符串没有闭合的引号");
                }
                i = close;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("表达式括号不匹配：第 " + (i + 1) + " 个字符处多了一个 )");
                }
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("表达式括号不匹配：缺少 " + depth + " 个 )");
        }
    }

    /** 语法检查 + 把列名换成 {@code up."Cn"}。找不到的标识符会报错并列出可用列。 */
    public static String translate(String expression, List<ColumnMeta> upstream) {
        checkSyntax(expression);
        List<ColumnMeta> sorted = new ArrayList<>(upstream == null ? List.of() : upstream);
        // 最长前缀优先：否则列 `订单` 会先把 `订单 金额` 吃掉一半
        sorted.sort(Comparator.comparingInt((ColumnMeta c) -> c.label().length()).reversed());

        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }

            ColumnMeta column = matchLabel(expression, i, sorted);
            if (column != null) {
                out.append("up.\"").append(column.physical()).append('"');
                i += column.label().length();
                continue;
            }

            if (Character.isDigit(c)) {
                int start = i;
                while (i < expression.length() && Character.isDigit(expression.charAt(i))) {
                    i++;
                }
                if (i < expression.length() && expression.charAt(i) == '.') {
                    i++;
                    while (i < expression.length() && Character.isDigit(expression.charAt(i))) {
                        i++;
                    }
                }
                out.append(expression, start, i);
                continue;
            }

            if (c == '\'') {
                int start = i;
                i++;
                while (i < expression.length() && expression.charAt(i) != '\'') {
                    i++;
                }
                i++; // 跳过收尾引号（checkSyntax 已确认存在）
                out.append(expression, start, i);
                continue;
            }

            if (i + 1 < expression.length() && TWO_CHAR_OPERATORS.contains(expression.substring(i, i + 2))) {
                out.append(expression, i, i + 2);
                i += 2;
                continue;
            }
            if (ONE_CHAR_OPERATORS.contains(String.valueOf(c))) {
                out.append(c);
                i++;
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    i++;
                }
                String word = expression.substring(start, i);
                String upper = word.toUpperCase(Locale.ROOT);
                if (CONSTANTS.contains(upper)) {
                    out.append(upper);
                    continue;
                }
                if (FUNCTIONS.contains(upper)) {
                    int j = i;
                    while (j < expression.length() && Character.isWhitespace(expression.charAt(j))) {
                        j++;
                    }
                    if (j < expression.length() && expression.charAt(j) == '(') {
                        out.append(upper);
                        continue;
                    }
                    throw new IllegalArgumentException("函数 " + word + " 后面缺少括号");
                }
                throw new IllegalArgumentException(
                        "表达式第 " + (start + 1) + " 个字符处的 `" + word + "` 既不是上游列，也不是允许的函数。"
                                + availableColumns(upstream));
            }

            throw new IllegalArgumentException(
                    "表达式第 " + (i + 1) + " 个字符处不允许出现 `" + c + "`");
        }
        return out.toString();
    }

    /**
     * 在 {@code pos} 处尝试匹配一个上游列名。
     *
     * <p>边界要求：列名后面不能紧跟字母/数字/下划线（否则 `amounts` 会被 `amount` 吃掉），
     * 也不能紧跟 {@code (}（那说明用户写的是同名函数，交给函数分支处理）。
     */
    private static ColumnMeta matchLabel(String expression, int pos, List<ColumnMeta> sorted) {
        for (ColumnMeta column : sorted) {
            String label = column.label();
            if (label == null || label.isEmpty()) {
                continue;
            }
            if (!expression.startsWith(label, pos)) {
                continue;
            }
            int end = pos + label.length();
            if (end < expression.length()) {
                char next = expression.charAt(end);
                if (Character.isLetterOrDigit(next) || next == '_' || next == '(') {
                    continue;
                }
            }
            return column;
        }
        return null;
    }

    private static String availableColumns(List<ColumnMeta> upstream) {
        if (upstream == null || upstream.isEmpty()) {
            return "上游还没有可用列，请先预览上游节点。";
        }
        List<String> labels = new ArrayList<>();
        for (ColumnMeta column : upstream) {
            labels.add(column.label());
            if (labels.size() == 12) {
                break;
            }
        }
        String suffix = upstream.size() > labels.size() ? " 等 " + upstream.size() + " 列" : "";
        return "可用列：" + String.join("、", labels) + suffix;
    }
}
