package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 cdc_source / cdc_target 合并成统一的 cdc_account。
 *
 * <p>用 Java 迁移而不是 SQL：target 的配置在 config_json 里，SQL 里拆 JSON 既不可读也容易踩 H2 方言。
 *
 * <p>源与目标共用 cdc_config_id 序列，id 天然不冲突，所以原 id 直搬、外键值不用改。
 * 旧表只重命名不删除，留一版回滚点；真正的清理放到后续版本。
 */
public class V13__Unified_accounts extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE cdc_account (
                      id BIGINT PRIMARY KEY,
                      name VARCHAR(160) NOT NULL,
                      account_type VARCHAR(32) NOT NULL,
                      host VARCHAR(255),
                      port INT,
                      database_name VARCHAR(255),
                      username VARCHAR(255),
                      password VARCHAR(1000),
                      extra_json CLOB,
                      enabled BOOLEAN NOT NULL DEFAULT TRUE,
                      created_at TIMESTAMP WITH TIME ZONE,
                      updated_at TIMESTAMP WITH TIME ZONE
                    )
                    """);
        }

        ObjectMapper json = new ObjectMapper();
        int sources = copySources(connection, json);
        int targets = copyTargets(connection, json);

        dropForeignKeys(connection);
        rename(connection, "cdc_source", "cdc_source_legacy");
        rename(connection, "cdc_target", "cdc_target_legacy");
        addForeignKeys(connection);

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM cdc_account")) {
            rs.next();
            int copied = rs.getInt(1);
            if (copied != sources + targets) {
                throw new IllegalStateException("账户迁移行数不一致：期望 " + (sources + targets) + "，实际 " + copied);
            }
        }
    }

    private int copySources(Connection connection, ObjectMapper json) throws Exception {
        int count = 0;
        try (Statement read = connection.createStatement();
             ResultSet rs = read.executeQuery("SELECT id,name,db_type,host,port,database_name,username,password," +
                     "poll_interval_ms,enabled,created_at FROM cdc_source ORDER BY id");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO cdc_account(id,name,account_type,host,port,database_name,username,password," +
                             "extra_json,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (rs.next()) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("pollIntervalMs", rs.getInt("poll_interval_ms"));
                bind(insert, rs.getLong("id"), rs.getString("name"), rs.getString("db_type"),
                        rs.getString("host"), (Integer) rs.getObject("port", Integer.class),
                        rs.getString("database_name"), rs.getString("username"), rs.getString("password"),
                        json.writeValueAsString(extra), rs.getBoolean("enabled"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class));
                insert.executeUpdate();
                count++;
            }
        }
        return count;
    }

    private int copyTargets(Connection connection, ObjectMapper json) throws Exception {
        int count = 0;
        try (Statement read = connection.createStatement();
             ResultSet rs = read.executeQuery("SELECT id,name,target_type,config_json,enabled,created_at " +
                     "FROM cdc_target ORDER BY id");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO cdc_account(id,name,account_type,host,port,database_name,username,password," +
                             "extra_json,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (rs.next()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = json.readValue(
                        rs.getString("config_json") == null ? "{}" : rs.getString("config_json"), Map.class);
                String type = switch (rs.getString("target_type")) {
                    case "STARROCKS_STREAM_LOAD" -> "STARROCKS";
                    default -> rs.getString("target_type");
                };
                Map<String, Object> extra = new LinkedHashMap<>();
                if ("STARROCKS".equals(type)) {
                    extra.put("endpoint", text(config.get("endpoint")));
                    extra.put("queryPort", number(config.get("queryPort")));
                }
                if ("KAFKA".equals(type)) {
                    extra.put("bootstrapServers", text(config.get("bootstrapServers")));
                    putIfPresent(extra, "saslUsername", text(config.get("saslUsername")));
                    putIfPresent(extra, "saslPassword", text(config.get("saslPassword")));
                }
                bind(insert, rs.getLong("id"), rs.getString("name"), type,
                        text(config.get("host")), number(config.get("port")),
                        text(config.get("database")), text(config.get("username")), text(config.get("password")),
                        json.writeValueAsString(extra), rs.getBoolean("enabled"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class));
                insert.executeUpdate();
                count++;
            }
        }
        return count;
    }

    private void bind(PreparedStatement statement, long id, String name, String type, String host, Integer port,
                      String database, String username, String password, String extra, boolean enabled,
                      java.time.OffsetDateTime createdAt) throws Exception {
        statement.setLong(1, id);
        statement.setString(2, name);
        statement.setString(3, type);
        statement.setString(4, host);
        if (port == null) {
            statement.setNull(5, java.sql.Types.INTEGER);
        } else {
            statement.setInt(5, port);
        }
        statement.setString(6, database);
        statement.setString(7, username);
        statement.setString(8, password);
        statement.setString(9, extra);
        statement.setBoolean(10, enabled);
        statement.setObject(11, createdAt);
        statement.setObject(12, createdAt);
    }

    private void dropForeignKeys(Connection connection) throws Exception {
        String[] statements = {
                "ALTER TABLE table_mapping DROP CONSTRAINT IF EXISTS fk_mapping_source",
                "ALTER TABLE table_mapping DROP CONSTRAINT IF EXISTS fk_mapping_target",
                "ALTER TABLE sync_task DROP CONSTRAINT IF EXISTS fk_task_source",
                "ALTER TABLE sync_task DROP CONSTRAINT IF EXISTS fk_task_target",
                "ALTER TABLE cdc_capture_group DROP CONSTRAINT IF EXISTS fk_capture_group_source",
                "ALTER TABLE cdc_target_subscription DROP CONSTRAINT IF EXISTS fk_subscription_target",
                "ALTER TABLE task_target_subscription DROP CONSTRAINT IF EXISTS fk_task_target_subscription_target"
        };
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private void rename(Connection connection, String from, String to) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + from + " RENAME TO " + to);
        }
    }

    private void addForeignKeys(Connection connection) throws Exception {
        String[] statements = {
                "ALTER TABLE table_mapping ADD CONSTRAINT fk_mapping_source FOREIGN KEY (source_id) REFERENCES cdc_account(id)",
                "ALTER TABLE table_mapping ADD CONSTRAINT fk_mapping_target FOREIGN KEY (target_id) REFERENCES cdc_account(id)",
                "ALTER TABLE sync_task ADD CONSTRAINT fk_task_source FOREIGN KEY (source_id) REFERENCES cdc_account(id)",
                "ALTER TABLE sync_task ADD CONSTRAINT fk_task_target FOREIGN KEY (target_id) REFERENCES cdc_account(id)",
                "ALTER TABLE cdc_capture_group ADD CONSTRAINT fk_capture_group_source FOREIGN KEY (source_id) REFERENCES cdc_account(id) ON DELETE CASCADE",
                "ALTER TABLE cdc_target_subscription ADD CONSTRAINT fk_subscription_target FOREIGN KEY (target_id) REFERENCES cdc_account(id) ON DELETE CASCADE",
                "ALTER TABLE task_target_subscription ADD CONSTRAINT fk_task_target_subscription_target FOREIGN KEY (target_id) REFERENCES cdc_account(id) ON DELETE CASCADE"
        };
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
