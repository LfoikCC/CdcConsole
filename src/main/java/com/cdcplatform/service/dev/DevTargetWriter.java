package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.TargetType;
import com.cdcplatform.service.ConnectionProbeService;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把临时表的结果写进目标库。
 *
 * <p>三种写入模式刻意不做成「一个 SQL 打天下」：
 * <ul>
 *   <li>覆盖用 {@code DELETE} 而不是 {@code TRUNCATE}——MySQL 的 TRUNCATE 需要 DROP 权限且隐式提交，
 *       SQL Server 的 TRUNCATE 需要 ALTER 权限，多数只读账号会在这里翻车。StarRocks 是唯一例外，
 *       它的 {@code TRUNCATE} 才是可靠的清空手段。</li>
 *   <li>按主键 upsert 在 MySQL 走 {@code ON DUPLICATE KEY UPDATE}，在 SQL Server 必须走
 *       {@code MERGE ... WITH (HOLDLOCK)}；不加 HOLDLOCK 的 MERGE 在并发下会死锁。</li>
 *   <li>StarRocks 走 MySQL 协议，主键模型下普通 INSERT 本身就是 upsert。</li>
 * </ul>
 */
@Service
public class DevTargetWriter {

    public enum WriteMode { OVERWRITE, APPEND, UPSERT }

    public record WriteResult(long written) {
    }

    /**
     * 目标表名与列名只禁止会破坏引用语法的字符。
     *
     * <p>真实表名可能带连字符或中文，用「字母数字下划线」的白名单会把正常配置挡在门外。
     */
    private static final Pattern UNSAFE_IDENTIFIER = Pattern.compile("[`\\[\\]\"';\\r\\n]");

    private final DataSource dataSource;
    private final ConnectionProbeService probe;
    private final DevProperties props;

    public DevTargetWriter(DataSource dataSource, ConnectionProbeService probe, DevProperties props) {
        this.dataSource = dataSource;
        this.probe = probe;
        this.props = props;
    }

    public WriteResult write(TargetConfig target, String table, WriteMode mode,
                             List<ColumnMeta> sourceColumns, String sourceTable,
                             List<String> targetColumns, List<String> keyColumns) {
        return write(target, table, mode, sourceColumns, sourceTable, targetColumns, keyColumns, () -> { });
    }

    /** 带取消检查点的写入：每批 commit 后调用 checkpoint，抛异常即回滚当前批并终止。 */
    public WriteResult write(TargetConfig target, String table, WriteMode mode,
                             List<ColumnMeta> sourceColumns, String sourceTable,
                             List<String> targetColumns, List<String> keyColumns, Runnable checkpoint) {
        if (target.targetType() == TargetType.KAFKA) {
            throw new IllegalArgumentException("数据库输出节点不能使用 Kafka 目标");
        }
        List<String> targets = targetColumns == null || targetColumns.isEmpty()
                ? sourceColumns.stream().map(ColumnMeta::label).toList()
                : targetColumns;
        if (targets.size() != sourceColumns.size()) {
            throw new IllegalArgumentException(
                    "目标列数（" + targets.size() + "）与上游列数（" + sourceColumns.size() + "）不一致");
        }
        targets.forEach(DevTargetWriter::checked);

        List<String> keys = mode == WriteMode.UPSERT
                ? (keyColumns == null ? List.of() : keyColumns)
                : List.of();
        if (mode == WriteMode.UPSERT && keys.isEmpty()) {
            throw new IllegalArgumentException("按主键 upsert 需要指定主键列");
        }
        keys.forEach(DevTargetWriter::checked);

        String insertSql = switch (mode) {
            case UPSERT -> upsertSql(target.targetType(), table, targets, keys);
            default -> insertSql(target.targetType(), table, targets);
        };

        String url = withTimeouts(probe.targetUrl(target));
        Map<String, Object> config = target.config();
        int batchSize = Math.max(1, props.getBatchSize());
        long written = 0;

        try (Connection h2 = dataSource.getConnection();
             Connection out = DriverManager.getConnection(url,
                     String.valueOf(config.get("username")), String.valueOf(config.get("password")))) {
            // 目标表只填了名字、库里还没有：按上游结构建出来再写
            ensureTable(out, target, table, targets, sourceColumns);
            out.setAutoCommit(false);
            if (mode == WriteMode.OVERWRITE) {
                try (Statement statement = out.createStatement()) {
                    statement.execute(target.targetType() == TargetType.STARROCKS_STREAM_LOAD
                            ? "TRUNCATE TABLE " + quote(target.targetType(), table)
                            : "DELETE FROM " + quote(target.targetType(), table));
                }
                out.commit();
            }

            try (Statement read = h2.createStatement();
                 ResultSet rs = read.executeQuery(
                         "SELECT * FROM " + sourceTable + " ORDER BY \"ORD\"");
                 PreparedStatement write = out.prepareStatement(insertSql)) {
                write.setQueryTimeout(120);
                int pending = 0;
                while (rs.next()) {
                    for (int i = 0; i < sourceColumns.size(); i++) {
                        write.setObject(i + 1, rs.getObject(sourceColumns.get(i).physical()));
                    }
                    write.addBatch();
                    pending++;
                    written++;
                    if (pending >= batchSize) {
                        write.executeBatch();
                        out.commit();
                        pending = 0;
                        checkpoint.run();
                    }
                }
                if (pending > 0) {
                    write.executeBatch();
                    out.commit();
                    checkpoint.run();
                }
            } catch (SQLException | RuntimeException e) {
                out.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入目标表 " + table + " 失败：" + e.getMessage(), e);
        }
        return new WriteResult(written);
    }

    // ---------------------------------------------------------------- 建表

    /**
     * 表不存在就按上游列建一张。
     *
     * <p>先用 {@code SELECT 1 ... WHERE 1=0} 探一下：这条语句不读数据，代价极小；
     * 失败才走建表。建表语句本身也是幂等的（MySQL / StarRocks 用 IF NOT EXISTS，
     * SQL Server 用 OBJECT_ID 判断），并发跑两个任务也不会互相撞。
     */
    private void ensureTable(Connection out, TargetConfig target, String table,
                             List<String> targets, List<ColumnMeta> sourceColumns) throws SQLException {
        String qualified = quote(target.targetType(), table);
        try (Statement probe = out.createStatement()) {
            probe.executeQuery("SELECT 1 FROM " + qualified + " WHERE 1=0").close();
            return;
        } catch (SQLException missing) {
            String ddl = createTableSql(target, table, targets, sourceColumns);
            try (Statement create = out.createStatement()) {
                create.execute(ddl);
            } catch (SQLException e) {
                throw new IllegalStateException("目标表 " + table + " 不存在，自动建表失败："
                        + e.getMessage() + "。DDL：" + ddl, e);
            }
        }
    }

    static String createTableSql(TargetConfig target, String table, List<String> targets,
                                 List<ColumnMeta> sourceColumns) {
        TargetType type = target.targetType();
        List<String> definitions = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            definitions.add(quote(type, targets.get(i)) + " " + columnType(type, sourceColumns.get(i).h2Type()));
        }
        String columns = String.join(", ", definitions);
        if (type == TargetType.STARROCKS_STREAM_LOAD) {
            // StarRocks 的建表必须有分桶：用第一列做 hash 桶，副本数按单副本，避免 BE 少时建不出来
            String first = quote(type, targets.get(0));
            return "CREATE TABLE " + quote(type, table) + " (" + columns + ") ENGINE=OLAP"
                    + " DUPLICATE KEY(" + first + ")"
                    + " DISTRIBUTED BY HASH(" + first + ") BUCKETS 1"
                    + " PROPERTIES(\"replication_num\" = \"1\")";
        }
        if (type == TargetType.SQLSERVER) {
            return "IF OBJECT_ID(N'" + table + "', N'U') IS NULL CREATE TABLE " + quote(type, table)
                    + " (" + columns + ")";
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(type, table) + " (" + columns + ")";
    }

    /** 上游列类型是 H2 的名字（VARCHAR / DECIMAL(38,10) …），这里翻成目标库的写法。 */
    static String columnType(TargetType type, String h2Type) {
        String value = h2Type == null ? "" : h2Type.trim().toUpperCase(java.util.Locale.ROOT);
        boolean sqlserver = type == TargetType.SQLSERVER;
        boolean starrocks = type == TargetType.STARROCKS_STREAM_LOAD;
        if (value.startsWith("DECIMAL") || value.startsWith("NUMERIC")) {
            return "DECIMAL(38,10)";
        }
        if (value.startsWith("BIGINT")) {
            return "BIGINT";
        }
        if (value.startsWith("SMALLINT")) {
            return "SMALLINT";
        }
        if (value.startsWith("TINYINT")) {
            return "TINYINT";
        }
        if (value.startsWith("INTEGER") || value.startsWith("INT")) {
            return "INT";
        }
        if (value.startsWith("DOUBLE") || value.startsWith("FLOAT") || value.startsWith("REAL")) {
            return sqlserver ? "FLOAT" : "DOUBLE";
        }
        if (value.startsWith("BOOLEAN")) {
            return sqlserver ? "BIT" : starrocks ? "BOOLEAN" : "TINYINT(1)";
        }
        if (value.startsWith("TIMESTAMP") || value.startsWith("DATETIME")) {
            return sqlserver ? "DATETIME2" : "DATETIME";
        }
        if (value.startsWith("DATE")) {
            return "DATE";
        }
        if (value.startsWith("TIME")) {
            return "TIME";
        }
        if (value.startsWith("VARBINARY") || value.startsWith("BINARY")) {
            return sqlserver ? "VARBINARY(MAX)" : starrocks ? "VARCHAR(1024)" : "VARBINARY(1024)";
        }
        if (value.startsWith("CLOB") || value.startsWith("TEXT")) {
            return sqlserver ? "NVARCHAR(MAX)" : starrocks ? "STRING" : "TEXT";
        }
        return sqlserver ? "NVARCHAR(1024)" : "VARCHAR(1024)";
    }

    // ---------------------------------------------------------------- SQL

    private static String insertSql(TargetType type, String table, List<String> columns) {
        List<String> quoted = columns.stream().map(c -> quote(type, c)).toList();
        return "INSERT INTO " + quote(type, table) + " (" + String.join(", ", quoted) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
    }

    private static String upsertSql(TargetType type, String table, List<String> columns, List<String> keys) {
        if (type == TargetType.SQLSERVER) {
            List<String> quoted = columns.stream().map(c -> "[" + c + "]").toList();
            String select = String.join(", ", quoted.stream().map(c -> "? AS " + c).toList());
            String on = String.join(" AND ", keys.stream().map(k -> "target.[" + k + "] = source.[" + k + "]").toList());
            List<String> updates = columns.stream().filter(c -> !keys.contains(c)).toList();
            StringBuilder sql = new StringBuilder("MERGE INTO ").append(quote(type, table))
                    .append(" WITH (HOLDLOCK) AS target USING (SELECT ").append(select).append(") AS source ON ")
                    .append(on);
            if (!updates.isEmpty()) {
                sql.append(" WHEN MATCHED THEN UPDATE SET ")
                        .append(String.join(", ", updates.stream()
                                .map(c -> "target.[" + c + "] = source.[" + c + "]").toList()));
            }
            sql.append(" WHEN NOT MATCHED THEN INSERT (").append(String.join(", ", quoted))
                    .append(") VALUES (").append(String.join(", ", quoted.stream()
                            .map(c -> "source." + c).toList())).append(");");
            return sql.toString();
        }

        List<String> quoted = columns.stream().map(c -> quote(type, c)).toList();
        String insert = "INSERT INTO " + quote(type, table) + " (" + String.join(", ", quoted) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        // StarRocks 的主键模型本身就按主键合并，普通 INSERT 即 upsert
        if (type == TargetType.STARROCKS_STREAM_LOAD) {
            return insert;
        }
        List<String> updates = columns.stream().filter(c -> !keys.contains(c)).toList();
        if (updates.isEmpty()) {
            // 只有主键列时，重复插入等价于什么都不做
            String key = keys.get(0);
            return insert + " ON DUPLICATE KEY UPDATE " + quote(type, key) + " = " + quote(type, key);
        }
        String assignments = String.join(", ", updates.stream()
                .map(c -> quote(type, c) + " = VALUES(" + quote(type, c) + ")").toList());
        return insert + " ON DUPLICATE KEY UPDATE " + assignments;
    }

    /** 目标端同样要超时：主机不可达时不能一直挂着占住运行额度。 */
    static String withTimeouts(String url) {
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

    static String quote(TargetType type, String identifier) {
        checked(identifier);
        return type == TargetType.SQLSERVER ? "[" + identifier + "]" : "`" + identifier + "`";
    }

    private static String checked(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 128
                || UNSAFE_IDENTIFIER.matcher(identifier).find()) {
            throw new IllegalArgumentException("非法标识符：" + identifier);
        }
        return identifier;
    }

    /** 供测试：列出某模式下会生成的 SQL 形状。 */
    public static List<String> describe(TargetType type, String table, List<String> columns, List<String> keys) {
        List<String> sql = new ArrayList<>();
        sql.add(insertSql(type, table, columns));
        if (keys != null && !keys.isEmpty()) {
            sql.add(upsertSql(type, table, columns, keys));
        }
        return sql;
    }
}
