package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点输出的落地层：每个节点的结果写进 H2 的 {@code DEV_TMP} 临时表，供下游节点读取。
 *
 * <p>两个设计决定值得记下来：
 * <ul>
 *   <li><b>物理列名固定为 {@code C1..Cn}</b>，显示名另存 {@link ColumnMeta#label()}。
 *       源库的列名可能带中文、空格、点号或撞上保留字，直接拼进 SQL 会一路踩标识符的坑；
 *       统一编号后所有算子 SQL 都可预测。</li>
 *   <li><b>每张表都带一个隐藏的 {@code ORD} 列</b>。H2 的 {@code INSERT..SELECT} 不保证行序，
 *       如果「排序」算子只改 SELECT 的 ORDER BY，下游一读就乱了。把顺序固化成一列，
 *       下游按 {@code ORD} 读取，排序语义才能跨节点保持。</li>
 * </ul>
 */
@Service
@org.springframework.context.annotation.Primary
public class DevTempTableStore {

    private static final Logger log = LoggerFactory.getLogger(DevTempTableStore.class);

    /** {@code index} 从 1 开始，对应物理列名 {@code C<index>}。 */
    public record ColumnMeta(int index, String label, String h2Type) {
        public String physical() {
            return "C" + index;
        }
    }

    public record PreviewRows(List<String> columns, List<List<Object>> rows, boolean truncated) {
    }

    private final DataSource dataSource;
    private final DevProperties props;

    public DevTempTableStore(DataSource dataSource, DevProperties props) {
        this.dataSource = dataSource;
        this.props = props;
    }

    /**
     * 启动时清一次。{@code DEV_TMP} 是持久 schema，上次进程崩溃留下的表会一直躺在
     * {@code cdc.mv.db} 里，日积月累就是磁盘问题。
     */
    @PostConstruct
    public void cleanupOnStartup() {
        if (!props.isCleanupOnStartup()) {
            return;
        }
        try {
            int dropped = dropAll();
            if (dropped > 0) {
                log.info("清理了 {} 张上次遗留的数据开发临时表", dropped);
            }
        } catch (Exception e) {
            log.warn("启动时清理 DEV_TMP 失败（不影响启动）：{}", e.getMessage());
        }
    }

    public String schema() {
        return props.getTempSchema();
    }

    /** 表名只保留安全字符，并附上原 key 的哈希，避免 {@code n-1} 与 {@code n_1} 撞名。 */
    public String tableName(long runId, String nodeKey) {
        String safe = nodeKey == null ? "node" : nodeKey.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.length() > 24) {
            safe = safe.substring(0, 24);
        }
        String hash = Integer.toHexString(String.valueOf(nodeKey).hashCode());
        return schema() + ".T_" + runId + "_" + safe + "_" + hash;
    }

    public void create(String table, List<ColumnMeta> columns) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(table).append(" (\"ORD\" BIGINT NOT NULL");
        for (ColumnMeta column : columns) {
            ddl.append(", ").append(quote(column.physical())).append(' ').append(column.h2Type());
        }
        ddl.append(")");
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema()));
                statement.execute(ensureColumnCatalog());
                statement.execute("DELETE FROM " + catalog() + " WHERE TABLE_NAME = " + literal(table));
                statement.execute(ddl.toString());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + catalog() + " (TABLE_NAME, IDX, LABEL, H2_TYPE) VALUES (?,?,?,?)")) {
                for (ColumnMeta column : columns) {
                    statement.setString(1, table);
                    statement.setInt(2, column.index());
                    statement.setString(3, column.label());
                    statement.setString(4, column.h2Type());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法创建临时表 " + table + "：" + e.getMessage(), e);
        }
    }

    /**
     * 原始列名单独存一张目录表。
     *
     * <p>物理列名是 {@code C1..Cn}，从结果集元数据只能读回编号；不另存一份，预览和下游就再也拿不到
     * 「订单金额」这样的真实列名了。
     */
    private String ensureColumnCatalog() {
        return "CREATE TABLE IF NOT EXISTS " + catalog() + " ("
                + "TABLE_NAME VARCHAR(160) NOT NULL, IDX INT NOT NULL, "
                + "LABEL VARCHAR(255) NOT NULL, H2_TYPE VARCHAR(64) NOT NULL, "
                + "PRIMARY KEY (TABLE_NAME, IDX))";
    }

    private String catalog() {
        return quote(schema()) + ".DEV_COLUMNS";
    }

    /**
     * 把 {@code rs} 的当前结果集灌进 {@code table}。返回写入行数。
     *
     * <p>按批提交，避免长事务占着 Hikari 连接（池只有 10 个）。
     */
    public long insert(String table, int columnCount, ResultSet rs, int maxRows) {
        if (columnCount <= 0) {
            throw new IllegalStateException("节点输出没有列，无法写入临时表");
        }
        int batchSize = Math.max(1, props.getBatchSize());
        String sql = insertSql(table, columnCount);
        long written = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                while (rs.next()) {
                    if (written >= maxRows) {
                        throw new IllegalStateException(
                                "节点输出超过 " + maxRows + " 行上限，请先加过滤条件或改用 SQL 输入");
                    }
                    written++;
                    statement.setLong(1, written);
                    for (int i = 1; i <= columnCount; i++) {
                        bind(statement, i + 1, rs, i);
                    }
                    statement.addBatch();
                    if (written % batchSize == 0) {
                        statement.executeBatch();
                        connection.commit();
                    }
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入临时表 " + table + " 失败：" + e.getMessage(), e);
        }
        return written;
    }

    /**
     * 改某个字段的显示名。
     *
     * <p>只动列目录里的 {@code LABEL}，物理列仍是 {@code C1..Cn}，所以不会重建表、
     * 也不会碰已经写进去的数据。
     */
    public void renameColumn(String table, int index, String label) {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ensureColumnCatalog());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + catalog() + " SET LABEL = ? WHERE TABLE_NAME = ? AND IDX = ?")) {
                statement.setString(1, label);
                statement.setString(2, table);
                statement.setInt(3, index);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新字段名称失败：" + e.getMessage(), e);
        }
    }

    public List<ColumnMeta> columnsOf(String table) {
        List<ColumnMeta> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT IDX, LABEL, H2_TYPE FROM " + catalog() + " WHERE TABLE_NAME = ? ORDER BY IDX")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(new ColumnMeta(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取临时表结构 " + table + "：" + e.getMessage(), e);
        }
        if (!columns.isEmpty()) {
            return columns;
        }
        // 目录缺失（例如手工建的临时表）时退回物理元数据，至少还能跑。
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            int index = 0;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if ("ORD".equalsIgnoreCase(meta.getColumnLabel(i))) {
                    continue;
                }
                columns.add(new ColumnMeta(++index, meta.getColumnLabel(i), h2TypeOf(meta.getColumnType(i))));
            }
            return columns;
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取临时表结构 " + table + "：" + e.getMessage(), e);
        }
    }

    public long rowCount(String table) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("无法统计临时表 " + table + "：" + e.getMessage(), e);
        }
    }

    /** 按 {@code ORD} 读取前 {@code limit} 行，用于预览。 */
    public PreviewRows preview(String table, int limit) {
        List<ColumnMeta> columns = columnsOf(table);
        List<String> labels = columns.stream().map(ColumnMeta::label).toList();
        List<List<Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " ORDER BY \"ORD\" LIMIT " + Math.max(1, limit + 1);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns.size());
                for (ColumnMeta column : columns) {
                    row.add(rs.getObject(column.physical()));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取临时表 " + table + "：" + e.getMessage(), e);
        }
        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = rows.subList(0, limit);
        }
        return new PreviewRows(labels, rows, truncated);
    }

    /** 供算子 SQL 使用的「按 ORD 读取上游」子查询。 */
    public static String sourceOrdered(String table) {
        return "(SELECT * FROM " + table + " ORDER BY \"ORD\")";
    }

    /**
     * 用一条 {@code SELECT} 的结果填满一张新临时表，返回写入行数。
     *
     * <p>{@code selectSql} 必须按 {@code ORD, C1..Cn} 的顺序产出列；列名由本方法负责。
     */
    public long materialize(String table, List<ColumnMeta> columns, String selectSql, List<Object> params) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (\"ORD\"");
        for (ColumnMeta column : columns) {
            sql.append(", ").append(quote(column.physical()));
        }
        sql.append(") ").append(selectSql);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("算子执行失败：" + e.getMessage(), e);
        }
    }

    /**
     * 批量写入外部数据（Excel 导入用）。{@code rows} 的顺序必须与 {@code columns} 一致。
     *
     * <p>与 {@link #insert} 的区别是不依赖 ResultSet，调用方可以边解析边喂行。
     */
    public long insertRows(String table, List<ColumnMeta> columns, Iterable<List<Object>> rows, int maxRows) {
        return insertRows(table, columns, rows, maxRows, 0);
    }

    /**
     * 追加写入，{@code ordStart} 是已有行数：追加时 ORD 要接着往下排，否则会撞主键。
     *
     * <p>{@code maxRows} 是整表上限，不单单是本次写入的行数。
     */
    public long insertRows(String table, List<ColumnMeta> columns, Iterable<List<Object>> rows, int maxRows,
                           long ordStart) {
        if (columns.isEmpty()) {
            throw new IllegalStateException("数据集没有列，无法写入");
        }
        int batchSize = Math.max(1, props.getBatchSize());
        String sql = insertSql(table, columns.size());
        long written = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (List<Object> row : rows) {
                    if (ordStart + written >= maxRows) {
                        throw new IllegalStateException("数据集超过 " + maxRows + " 行上限");
                    }
                    written++;
                    statement.setLong(1, ordStart + written);
                    for (int i = 0; i < columns.size(); i++) {
                        statement.setObject(i + 2, row.get(i));
                    }
                    statement.addBatch();
                    if (written % batchSize == 0) {
                        statement.executeBatch();
                        connection.commit();
                    }
                }
                statement.executeBatch();
                connection.commit();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入数据集失败：" + e.getMessage(), e);
        }
        return written;
    }

    /** 按 {@code ORD} 分页读取，用于数据集详情页的数据明细。 */
    public PreviewRows page(String table, int offset, int limit) {
        List<ColumnMeta> columns = columnsOf(table);
        List<String> labels = columns.stream().map(ColumnMeta::label).toList();
        List<List<Object>> rows = new ArrayList<>();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT * FROM " + table + " ORDER BY \"ORD\" LIMIT " + safeLimit + " OFFSET " + safeOffset;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns.size());
                for (ColumnMeta column : columns) {
                    row.add(rs.getObject(column.physical()));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取数据集数据：" + e.getMessage(), e);
        }
        return new PreviewRows(labels, rows, false);
    }

    /** 某一列已有的值，追加数据时用来跳过重复行。 */
    public java.util.Set<String> valuesOf(String table, ColumnMeta column) {
        java.util.Set<String> values = new java.util.HashSet<>();
        String sql = "SELECT DISTINCT " + quote(column.physical()) + " FROM " + table
                + " WHERE " + quote(column.physical()) + " IS NOT NULL";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(String.valueOf(rs.getObject(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取去重主键：" + e.getMessage(), e);
        }
        return values;
    }

    public record SqlResult(String table, long rows, List<ColumnMeta> columns) {
    }

    /**
     * 为 SQL 算子建一个带逻辑列名的视图。
     *
     * <p>物理列是 {@code C1..Cn}，用户不可能按编号写 SQL。视图把列名映射回真实名称，
     * 并统一转成大写——H2 会把未加引号的标识符折成大写，这样 {@code SELECT id} 正好命中 {@code "ID"}。
     */
    public String createView(String viewName, String sourceTable, List<ColumnMeta> columns) {
        StringBuilder sql = new StringBuilder("CREATE OR REPLACE VIEW ").append(viewName).append(" AS SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            ColumnMeta column = columns.get(i);
            sql.append(quote(column.physical())).append(" AS ")
                    .append(quote(column.label().toUpperCase(java.util.Locale.ROOT)));
        }
        sql.append(" FROM ").append(sourceTable);
        execute(sql.toString());
        return viewName;
    }

    public void dropView(String viewName) {
        execute("DROP VIEW IF EXISTS " + viewName);
    }

    public SqlResult runSql(String table, String sql, int maxRows) {
        return runSql(table, sql, maxRows, List.of());
    }

    /**
     * 执行一段自定义 SELECT 并落成临时表，列从结果集元数据推断（SQL 算子用）。
     *
     * <p>先跑一次 {@code WHERE 1=0} 只拿列，再正式执行：避免在打开着结果集的同时对同一张表做 DDL。
     * {@code known} 用来把视图的大写列名还原成用户看到的原始列名。
     */
    public SqlResult runSql(String table, String sql, int maxRows, List<ColumnMeta> known) {
        List<ColumnMeta> columns = describe(sql, known);
        if (columns.isEmpty()) {
            throw new IllegalStateException("SQL 没有返回任何列");
        }
        create(table, columns);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            long rows = insert(table, columns.size(), rs, maxRows);
            return new SqlResult(table, rows, columns);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 算子执行失败：" + e.getMessage(), e);
        }
    }

    private List<ColumnMeta> describe(String sql, List<ColumnMeta> known) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String probe = "SELECT * FROM (" + trimmed + ") x WHERE 1=0";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(probe);
             ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            List<ColumnMeta> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                if (label == null || label.isBlank()) {
                    label = "col_" + i;
                }
                columns.add(new ColumnMeta(i, restoreLabel(label, known),
                        h2TypeOf(meta.getColumnType(i))));
            }
            return columns;
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 算子执行失败：" + e.getMessage(), e);
        }
    }

    private static String restoreLabel(String label, List<ColumnMeta> known) {
        for (ColumnMeta column : known) {
            if (column.label().equalsIgnoreCase(label)) {
                return column.label();
            }
        }
        return label;
    }

    /** 只执行一条 DDL/DML，用于「删除重建」这类操作。 */
    public void execute(String sql) {        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败：" + e.getMessage(), e);
        }
    }

    public void drop(String table) {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM " + catalog() + " WHERE TABLE_NAME = " + literal(table));
            }
        } catch (SQLException e) {
            log.warn("删除临时表 {} 失败：{}", table, e.getMessage());
        }
    }

    /** 清空整个 DEV_TMP，返回删除的表数。 */
    public int dropAll() {
        List<String> tables = new ArrayList<>();
        List<String> views = new ArrayList<>();
        String schema = schema();
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(ensureColumnCatalog());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?")) {
                statement.setString(1, schema.toUpperCase());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if ("VIEW".equalsIgnoreCase(rs.getString(2))) {
                            views.add(name);
                        } else {
                            tables.add(name);
                        }
                    }
                }
            }
            for (String view : views) {
                try (Statement drop = connection.createStatement()) {
                    drop.execute("DROP VIEW IF EXISTS " + quote(schema) + "." + quote(view));
                }
            }
            for (String table : tables) {
                // 目录表自己也在 DEV_TMP 里；先删掉它就轮不到后面的 DELETE 了
                if ("DEV_COLUMNS".equalsIgnoreCase(table)) {
                    continue;
                }
                try (Statement drop = connection.createStatement()) {
                    drop.execute("DROP TABLE IF EXISTS " + quote(schema) + "." + quote(table));
                }
            }
            try (Statement clear = connection.createStatement()) {
                clear.execute("DELETE FROM " + catalog());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清理临时表失败：" + e.getMessage(), e);
        }
        return tables.size();
    }

    // -------------------------------------------------------------- internals

    private static String insertSql(String table, int columnCount) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (\"ORD\"");
        for (int i = 1; i <= columnCount; i++) {
            sql.append(", ").append(quote("C" + i));
        }
        sql.append(") VALUES (?");
        sql.append(", ?".repeat(columnCount));
        return sql.append(")").toString();
    }

    /**
     * 按 H2 目标类型选取值方式：二进制走 {@code getBytes}，文本走 {@code getString}，
     * 其余用 {@code getObject}。MySQL 的 BLOB 会返回 {@code java.sql.Blob}，H2 无法直接绑定。
     */
    private static void bind(PreparedStatement statement, int parameter, ResultSet rs, int column)
            throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int type = meta.getColumnType(column);
        switch (type) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    statement.setBytes(parameter, rs.getBytes(column));
            case Types.CLOB, Types.NCLOB ->
                    statement.setString(parameter, rs.getString(column));
            default -> statement.setObject(parameter, rs.getObject(column));
        }
    }

    static String h2TypeOf(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.DECIMAL, Types.NUMERIC -> "DECIMAL(38,10)";
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> "DOUBLE";
            case Types.BIT, Types.BOOLEAN -> "BOOLEAN";
            case Types.DATE -> "DATE";
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "VARBINARY";
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "CLOB";
            default -> "VARCHAR";
        };
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
