package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.service.ConnectionProbeService;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 两个输入节点：读一张表，或跑一条 SELECT。
 *
 * <p>连接一律用 {@link ConnectionProbeService#streamingUrl} 打开。用普通 {@code url()}
 * 会让 MySQL 驱动把整个结果集缓冲进内存——大表直接 OOM，而且是那种「小表测试全绿、上线就崩」的故障。
 */
@Service
public class DevNodeReader {

    /**
     * 标识符里不允许出现的字符：引号、分号与换行。
     *
     * <p>不能只放行「字母数字下划线」——真实的库表名经常带连字符（`CDC-SOURCE`）或中文，
     * 那种白名单会把正常配置挡在门外。这里改为「禁止会破坏引用语法的字符」，其余一律加引号处理。
     */
    private static final Pattern UNSAFE_IDENTIFIER = Pattern.compile("[`\\[\\]\"';\\r\\n]");
    private static final Pattern LEADING_KEYWORD = Pattern.compile("(?is)^(select|with)\\b");

    private final ConnectionProbeService probe;
    private final DevTempTableStore store;
    private final DevProperties props;

    public DevNodeReader(ConnectionProbeService probe, DevTempTableStore store, DevProperties props) {
        this.probe = probe;
        this.store = store;
        this.props = props;
    }

    public record ReadResult(String table, long rows, List<ColumnMeta> columns) {
    }

    /** 数据集预览：只读、不落临时表。 */
    public record PreviewData(List<String> columns, List<List<Object>> rows, boolean truncated) {
    }

    /** 只取字段，不读数据：数据集创建/刷新字段用。 */
    public List<ColumnMeta> describeTable(SourceConfig source, String database, String table) {
        String sql = "SELECT * FROM " + qualified(source.dbType(), blankToNull(database), table) + " WHERE 1=0";
        return describe(source, database, sql);
    }

    public List<ColumnMeta> describeSql(SourceConfig source, String database, String sql) {
        String cleaned = stripComments(sql);
        if (!LEADING_KEYWORD.matcher(cleaned).find()) {
            throw new IllegalArgumentException("数据集 SQL 只支持 SELECT 或 WITH 查询");
        }
        if (hasMultipleStatements(cleaned)) {
            throw new IllegalArgumentException("数据集 SQL 只能包含一条查询语句");
        }
        return describe(source, database, "SELECT * FROM (" + cleaned + ") x WHERE 1=0");
    }

    public PreviewData previewTable(SourceConfig source, String database, String table, int limit) {
        String sql = "SELECT * FROM " + qualified(source.dbType(), blankToNull(database), table);
        return preview(source, database, sql, limit);
    }

    public PreviewData previewSql(SourceConfig source, String database, String sql, int limit) {
        String cleaned = stripComments(sql);
        if (!LEADING_KEYWORD.matcher(cleaned).find() || hasMultipleStatements(cleaned)) {
            throw new IllegalArgumentException("数据集 SQL 只支持单条 SELECT 或 WITH 查询");
        }
        return preview(source, database, cleaned, limit);
    }

    private List<ColumnMeta> describe(SourceConfig source, String database, String sql) {
        try (Connection connection = open(source, database);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return columnsOf(rs.getMetaData());
        } catch (SQLException e) {
            throw new IllegalStateException("读取字段失败：" + e.getMessage(), e);
        }
    }

    private PreviewData preview(SourceConfig source, String database, String sql, int limit) {
        int safe = Math.max(1, Math.min(limit, 500));
        try (Connection connection = open(source, database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(safe + 1);
            statement.setFetchSize(Math.min(1000, safe + 1));
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= safe) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(columns.size());
                    for (int i = 1; i <= columns.size(); i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new PreviewData(columns, rows, truncated);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("预览数据集失败：" + e.getMessage(), e);
        }
    }

    private Connection open(SourceConfig source, String database) throws SQLException {
        Connection connection = DriverManager.getConnection(
                withTimeouts(probe.streamingUrl(source, database), source.dbType()),
                source.username(), source.password());
        connection.setReadOnly(true);
        return connection;
    }

    /** 读取源表。{@code columns} 为空表示全列；{@code where} 可为空。 */
    public ReadResult readTable(SourceConfig source, String database, String table, List<String> columns,
                                String where, long runId, String nodeKey, int maxRows) {
        String schema = blankToNull(database);
        String sql = "SELECT " + selectList(columns, source.dbType()) + " FROM " + qualified(source.dbType(), schema, table);
        String filter = blankToNull(where);
        if (filter != null) {
            if (filter.contains(";")) {
                throw new IllegalArgumentException("过滤条件不能包含分号");
            }
            sql += " WHERE " + filter;
        }
        return run(source, schema, sql, runId, nodeKey, maxRows, null);
    }

    /** 执行自定义 SELECT。只允许单条查询语句。 */
    public ReadResult readSql(SourceConfig source, String database, String sql, long runId, String nodeKey, int maxRows) {
        String cleaned = stripComments(sql);
        if (!LEADING_KEYWORD.matcher(cleaned).find()) {
            throw new IllegalArgumentException("SQL 节点只支持 SELECT 或 WITH 查询");
        }
        if (hasMultipleStatements(cleaned)) {
            throw new IllegalArgumentException("SQL 节点只能包含一条查询语句");
        }
        return run(source, blankToNull(database), cleaned, runId, nodeKey, maxRows, null);
    }

    private ReadResult run(SourceConfig source, String database, String sql, long runId, String nodeKey,
                           int maxRows, List<Object> params) {
        String table = store.tableName(runId, nodeKey);
        store.drop(table);
        try (Connection connection = DriverManager.getConnection(
                withTimeouts(probe.streamingUrl(source, database), source.dbType()),
                source.username(), source.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(Math.min(1000, Math.max(1, props.getBatchSize())));
                statement.setMaxRows(maxRows);
                if (params != null) {
                    for (int i = 0; i < params.size(); i++) {
                        statement.setObject(i + 1, params.get(i));
                    }
                }
                try (ResultSet rs = statement.executeQuery()) {
                    List<ColumnMeta> columns = columnsOf(rs.getMetaData());
                    if (columns.isEmpty()) {
                        throw new IllegalStateException("查询没有返回任何列");
                    }
                    store.create(table, columns);
                    long rows = store.insert(table, columns.size(), rs, maxRows);
                    return new ReadResult(table, rows, columns);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取源数据失败：" + e.getMessage(), e);
        }
    }

    private static List<ColumnMeta> columnsOf(ResultSetMetaData meta) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                label = "col_" + i;
            }
            columns.add(new ColumnMeta(i, label, DevTempTableStore.h2TypeOf(meta.getColumnType(i))));
        }
        return columns;
    }

    private static String selectList(List<String> columns, DatabaseType type) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quote(type, column));
        }
        return String.join(", ", quoted);
    }

    static String quote(DatabaseType type, String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 128
                || UNSAFE_IDENTIFIER.matcher(identifier).find()) {
            throw new IllegalArgumentException("非法标识符：" + identifier);
        }
        return type == DatabaseType.SQLSERVER ? "[" + identifier + "]" : "`" + identifier + "`";
    }

    static String qualified(DatabaseType type, String database, String table) {
        String quotedTable = quote(type, table);
        if (database == null) {
            return quotedTable;
        }
        return quote(type, database) + "." + quotedTable;
    }

    private static String stripComments(String sql) {
        if (sql == null) {
            return "";
        }
        String withoutBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("(?m)--.*$", " ").trim();
    }

    /** 去掉末尾分号后仍含分号，说明是多条语句。 */
    private static boolean hasMultipleStatements(String sql) {
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.contains(";");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 给连接串补上超时。
     *
     * <p>没有超时的 JDBC 连接在主机不可达时会一直等操作系统放弃（动辄数分钟），
     * 而数据流是串行执行、并发额度有限的：一个死掉的主机就能把整个模块卡住。
     */
    static String withTimeouts(String url, DatabaseType type) {
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

    /** 供测试与调试：暴露一条已清洗的 SQL 是否可执行。 */
    public static boolean isReadOnlyQuery(String sql) {
        String cleaned = stripComments(sql);
        return LEADING_KEYWORD.matcher(cleaned).find() && !hasMultipleStatements(cleaned);
    }

    /** 只用于单测：把 Statement 的读取方式统一到一处，便于替换。 */
    static void applyStreaming(Statement statement, int batchSize) throws SQLException {
        statement.setFetchSize(Math.min(1000, Math.max(1, batchSize)));
    }
}
