package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.service.ConnectionProbeService;
import com.cdcplatform.service.sql.SqlIdentifiers;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 两个输入节点：读一张表，或跑一条 SELECT。
 *
 * <p>连接一律用 {@link ConnectionProbeService#streamingUrl} 打开。用普通 {@code url()}
 * 会让 MySQL 驱动把整个结果集缓冲进内存——大表直接 OOM，而且是那种「小表测试全绿、上线就崩」的故障。
 */
@Service
public class DevNodeReader {

    /** 只看开头是不是 SELECT / WITH；引用规则与校验都在 {@link SqlIdentifiers}。 */
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

    /** 数据集明细的一页：列名 + 行，行数不超过请求的 limit。 */
    public record PageData(List<String> columns, List<List<Object>> rows) {
    }

    /** 只取字段，不读数据：数据集创建/刷新字段用。 */
    public List<ColumnMeta> describeTable(SourceConfig source, String database, String table) {
        String sql = "SELECT * FROM " + qualified(source.dbType(), effectiveDatabase(source, database), table) + " WHERE 1=0";
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
        return describe(source, database,
                "SELECT * FROM (" + quoteQualifiedNames(cleaned, source.dbType()) + ") x WHERE 1=0");
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

    // ------------------------------------------------------- 数据明细的分页

    /**
     * 数据源里给字段配的注释：{@code 原始字段名 -> 注释}，没写注释的字段不在结果里。
     *
     * <p>只有物理表有注释；SQL 查询出来的列没有可读的注释来源。
     */
    public Map<String, String> columnComments(SourceConfig source, String database, String table) {
        return source.dbType() == DatabaseType.SQLSERVER
                ? sqlServerComments(source, database, table)
                : mysqlComments(source, database, table);
    }

    /** MySQL / StarRocks：{@code SHOW FULL COLUMNS} 直接带 Comment 列，比翻 information_schema 稳。 */
    private Map<String, String> mysqlComments(SourceConfig source, String database, String table) {
        String sql = "SHOW FULL COLUMNS FROM "
                + qualified(source.dbType(), effectiveDatabase(source, database), table);
        Map<String, String> comments = new LinkedHashMap<>();
        try (Connection connection = open(source, database);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("Field");
                String comment = rs.getString("Comment");
                if (name != null && comment != null && !comment.isBlank()) {
                    comments.put(name, comment.trim());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取字段注释失败：" + e.getMessage(), e);
        }
        return comments;
    }

    /** SQL Server 的注释存在扩展属性 MS_Description 上。 */
    private Map<String, String> sqlServerComments(SourceConfig source, String database, String table) {
        String schema = "dbo";
        String name = table == null ? "" : table.trim();
        int dot = name.indexOf('.');
        if (dot > 0) {
            schema = name.substring(0, dot);
            name = name.substring(dot + 1);
        }
        String sql = """
                SELECT c.name AS COLUMN_NAME, CAST(ep.value AS NVARCHAR(4000)) AS COLUMN_COMMENT
                  FROM sys.columns c
                  JOIN sys.objects o ON o.object_id = c.object_id
                  JOIN sys.schemas s ON s.schema_id = o.schema_id
                  LEFT JOIN sys.extended_properties ep
                         ON ep.major_id = c.object_id AND ep.minor_id = c.column_id
                        AND ep.name = 'MS_Description'
                 WHERE o.name = ? AND s.name = ?""";
        Map<String, String> comments = new LinkedHashMap<>();
        try (Connection connection = open(source, database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    String comment = rs.getString("COLUMN_COMMENT");
                    if (column != null && comment != null && !comment.isBlank()) {
                        comments.put(column, comment.trim());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取字段注释失败：" + e.getMessage(), e);
        }
        return comments;
    }


    /** 数据集明细一共多少行（直连数据集没有本地行数，只能问数据源）。 */
    public long countTable(SourceConfig source, String database, String table) {
        return count(source, database,
                "SELECT COUNT(*) FROM " + qualified(source.dbType(), effectiveDatabase(source, database), table));
    }

    public long countSql(SourceConfig source, String database, String sql) {
        return count(source, database, "SELECT COUNT(*) FROM (" + selectOnly(sql, source) + ") src");
    }

    /**
     * 取第 {@code offset} 行起的一页。
     *
     * <p>这里不用 {@code setMaxRows(offset+limit)} 再跳过前 offset 行：翻到后面几页时
     * 每翻一页都要把整张表重新读一遍，几千页下来等于把库拖死。
     */
    public PageData pageTable(SourceConfig source, String database, String table, int offset, int limit) {
        String sql = "SELECT * FROM " + qualified(source.dbType(), effectiveDatabase(source, database), table);
        return page(source, database, sql, offset, limit);
    }

    public PageData pageSql(SourceConfig source, String database, String sql, int offset, int limit) {
        return page(source, database, "SELECT * FROM (" + selectOnly(sql, source) + ") src", offset, limit);
    }

    /** 用户 SQL 只允许单条只读查询，包进子查询前统一校验一遍。 */
    private static String selectOnly(String sql, SourceConfig source) {
        String cleaned = stripComments(sql);
        if (!LEADING_KEYWORD.matcher(cleaned).find() || hasMultipleStatements(cleaned)) {
            throw new IllegalArgumentException("数据集 SQL 只支持单条 SELECT 或 WITH 查询");
        }
        return quoteQualifiedNames(cleaned, source.dbType());
    }

    private long count(SourceConfig source, String database, String sql) {
        try (Connection connection = open(source, database);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计数据集行数失败：" + e.getMessage(), e);
        }
    }

    private PageData page(SourceConfig source, String database, String sql, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        // SQL Server 的 OFFSET/FETCH 必须带 ORDER BY；不指定排序时用 (SELECT NULL) 占位
        String paged = source.dbType() == DatabaseType.SQLSERVER
                ? sql + " ORDER BY (SELECT NULL) OFFSET " + safeOffset + " ROWS FETCH NEXT " + safeLimit + " ROWS ONLY"
                : sql + " LIMIT " + safeLimit + " OFFSET " + safeOffset;
        try (Connection connection = open(source, database);
             PreparedStatement statement = connection.prepareStatement(paged)) {
            statement.setFetchSize(safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columns.size());
                    for (int i = 1; i <= columns.size(); i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new PageData(columns, rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取数据集明细失败：" + e.getMessage(), e);
        }
    }

    private Connection open(SourceConfig source, String database) throws SQLException {
        Connection connection = DriverManager.getConnection(
                withTimeouts(probe.streamingUrl(source, effectiveDatabase(source, database)), source.dbType()),
                source.username(), source.password());
        connection.setReadOnly(true);
        return connection;
    }

    /** 读取源表。{@code columns} 为空表示全列；{@code where} 可为空。 */
    public ReadResult readTable(SourceConfig source, String database, String table, List<String> columns,
                                String where, long runId, String nodeKey, int maxRows) {
        String schema = effectiveDatabase(source, database);
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
        return run(source, effectiveDatabase(source, database), quoteQualifiedNames(cleaned, source.dbType()),
                runId, nodeKey, maxRows, null);
    }

    private ReadResult run(SourceConfig source, String database, String sql, long runId, String nodeKey,
                           int maxRows, List<Object> params) {
        String table = store.tableName(runId, nodeKey);
        store.drop(table);
        try (Connection connection = DriverManager.getConnection(
                withTimeouts(probe.streamingUrl(source, effectiveDatabase(source, database)), source.dbType()),
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

    /** 引用规则见 {@link SqlIdentifiers}：数据开发与数据分析必须共用同一套。 */
    static String quote(DatabaseType type, String identifier) {
        return SqlIdentifiers.quote(type, identifier);
    }

    static String qualified(DatabaseType type, String database, String table) {
        return SqlIdentifiers.qualified(type, database, table);
    }

    static String quoteQualifiedNames(String sql, DatabaseType type) {
        return SqlIdentifiers.quoteQualifiedNames(sql, type);
    }

    private static String stripComments(String sql) {
        return SqlIdentifiers.stripComments(sql);
    }

    private static boolean hasMultipleStatements(String sql) {
        return SqlIdentifiers.hasMultipleStatements(sql);
    }

    private static String effectiveDatabase(SourceConfig source, String database) {
        String value = blankToNull(database);
        return value != null ? value : blankToNull(source.databaseName());
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
        return SqlIdentifiers.withTimeouts(url, type);
    }

    /** 供测试与调试：暴露一条已清洗的 SQL 是否可执行。 */
    public static boolean isReadOnlyQuery(String sql) {
        return SqlIdentifiers.isReadOnlyQuery(sql);
    }

    /** 只用于单测：把 Statement 的读取方式统一到一处，便于替换。 */
    static void applyStreaming(Statement statement, int batchSize) throws SQLException {
        statement.setFetchSize(Math.min(1000, Math.max(1, batchSize)));
    }
}
