package com.cdcplatform.service;

import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.model.Types.TargetType;
import org.springframework.stereotype.Service;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;

import java.sql.DriverManager;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Service
public class ConnectionProbeService {
    public Map<String, Object> probe(SourceConfig source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("reachable", false);
        result.put("cdcEnabled", false);
        result.put("cdcCheckApplicable", true);
        result.put("capability", source.dbType() == DatabaseType.STARROCKS ? "POLLING_ONLY" : "STRICT_CDC");
        try (var connection = DriverManager.getConnection(url(source), source.username(), source.password())) {
            result.put("reachable", true);
            if (source.dbType() == DatabaseType.STARROCKS) {
                try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT VERSION() AS detail")) {
                    if (rs.next()) result.put("detail", "StarRocks " + rs.getString("detail") + ", snapshot polling enabled");
                }
                result.put("cdcEnabled", true);
                return result;
            }
            var sql = source.dbType() == DatabaseType.MYSQL
                    ? "SELECT @@GLOBAL.log_bin AS enabled, @@GLOBAL.binlog_format AS detail, @@GLOBAL.binlog_row_image AS row_image"
                    : "SELECT COUNT(*) AS enabled, CAST(COUNT(*) AS VARCHAR) AS detail FROM sys.databases WHERE is_cdc_enabled=1 AND HAS_DBACCESS(name)=1";
            try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
                if (rs.next()) {
                    boolean enabled = source.dbType() == DatabaseType.MYSQL
                            ? rs.getBoolean("enabled") && "ROW".equalsIgnoreCase(rs.getString("detail"))
                                && "FULL".equalsIgnoreCase(rs.getString("row_image"))
                            : rs.getBoolean("enabled");
                    result.put("cdcEnabled", enabled);
                    result.put("detail", source.dbType() == DatabaseType.MYSQL
                            ? "binlog=" + rs.getString("detail") + ", row_image=" + rs.getString("row_image")
                            : "CDC-enabled databases=" + rs.getString("detail"));
                }
            }
        } catch (Exception e) {
            result.put("error", rootMessage(e));
        }
        return result;
    }

    public String url(SourceConfig s) {
        return url(s, s.databaseName());
    }

    public String url(SourceConfig s, String database) {
        String selected = database == null ? "" : database.trim();
        return switch (s.dbType()) {
            case MYSQL, STARROCKS -> "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC".formatted(s.host(), s.port(), selected);
            case SQLSERVER -> "jdbc:sqlserver://%s:%d%s;encrypt=true;trustServerCertificate=true".formatted(
                    s.host(), s.port(), selected.isEmpty() ? "" : ";databaseName=" + selected);
        };
    }

    public String streamingUrl(SourceConfig source, String database) {
        String url = url(source, database);
        return source.dbType() == DatabaseType.SQLSERVER ? url : url + "&useCursorFetch=true&defaultFetchSize=1000";
    }

    public List<String> databases(SourceConfig source) {
        var result = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(url(source, null), source.username(), source.password());
             var statement = connection.createStatement();
             var rs = statement.executeQuery(source.dbType() == DatabaseType.SQLSERVER
                     ? "SELECT name FROM sys.databases WHERE state_desc='ONLINE' AND HAS_DBACCESS(name)=1 ORDER BY name"
                     : "SHOW DATABASES")) {
            while (rs.next()) result.add(rs.getString(1));
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("Unable to read database list: " + rootMessage(e), e); }
    }

    public boolean cdcEnabledForTable(SourceConfig source, String database, String schema, String table) {
        if (source.dbType() != DatabaseType.SQLSERVER) return true;
        String sql = "SELECT COUNT(*) FROM cdc.change_tables ct JOIN sys.tables t ON t.object_id=ct.source_object_id " +
                "JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?";
        try (var connection = DriverManager.getConnection(url(source, database), source.username(), source.password());
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema == null || schema.isBlank() ? "dbo" : schema);
            statement.setString(2, table);
            try (var rs = statement.executeQuery()) { rs.next(); return rs.getInt(1) > 0; }
        } catch (Exception e) { throw new IllegalArgumentException("无法检查 SQL Server 表级 CDC：" + rootMessage(e), e); }
    }

    public List<Map<String, Object>> tables(SourceConfig source) {
        return tables(source, source.databaseName());
    }

    public List<Map<String, Object>> tables(SourceConfig source, String database) {
        if (database == null || database.isBlank()) throw new IllegalArgumentException("Source database is required");
        try (var connection = DriverManager.getConnection(url(source, database), source.username(), source.password())) {
            return readTables(connection, database);
        } catch (Exception e) { throw new IllegalArgumentException("Unable to read database metadata: " + rootMessage(e), e); }
    }

    public List<Map<String, Object>> tables(TargetConfig target) {
        if (target.targetType() != TargetType.MYSQL && target.targetType() != TargetType.SQLSERVER &&
                target.targetType() != TargetType.STARROCKS_STREAM_LOAD)
            throw new IllegalArgumentException("This target type does not provide JDBC table metadata");
        var c = target.config();
        String database = required(c, "database"), username = required(c, "username"), password = required(c, "password");
        String url = targetUrl(target);
        try (var connection = DriverManager.getConnection(url, username, password)) {
            return readTables(connection, database);
        } catch (Exception e) { throw new IllegalArgumentException("Unable to read target metadata: " + rootMessage(e), e); }
    }

    public List<String> topics(TargetConfig target) {
        if (target.targetType() != TargetType.KAFKA) throw new IllegalArgumentException("该目标不是 Kafka 连接");
        var options = new Properties();
        options.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, required(target.config(), "bootstrapServers"));
        options.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
        options.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        try (var admin = AdminClient.create(options)) {
            return admin.listTopics().names().get(6, TimeUnit.SECONDS).stream().sorted().toList();
        } catch (Exception e) { throw new IllegalArgumentException("无法读取 Kafka Topic：" + rootMessage(e), e); }
    }

    public String targetUrl(TargetConfig target) {
        var c = target.config();
        String database = required(c, "database");
        String host;
        int port;
        if (target.targetType() == TargetType.STARROCKS_STREAM_LOAD) {
            var endpoint = URI.create(required(c, "endpoint"));
            host = endpoint.getHost();
            int httpPort = endpoint.getPort() > 0 ? endpoint.getPort() : 8030;
            port = Integer.parseInt(String.valueOf(c.getOrDefault("queryPort", httpPort + 1000)));
        } else {
            host = required(c, "host");
            port = Integer.parseInt(String.valueOf(c.getOrDefault("port", target.targetType() == TargetType.MYSQL ? 3306 : 1433)));
        }
        return target.targetType() == TargetType.SQLSERVER
                ? "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true".formatted(host, port, database)
                : "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC".formatted(host, port, database);
    }

    private List<Map<String, Object>> readTables(java.sql.Connection connection, String catalog) throws Exception {
        if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("microsoft"))
            return readMysqlCompatibleTables(connection, catalog);
        var result = new ArrayList<Map<String, Object>>();
        var metadata = connection.getMetaData();
        try (var tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String schema = tables.getString("TABLE_SCHEM"), table = tables.getString("TABLE_NAME");
                    var columns = new ArrayList<String>();
                    var details = new ArrayList<Map<String, Object>>();
                    try (var rs = metadata.getColumns(catalog, schema, table, "%")) {
                        while (rs.next()) {
                            String name = rs.getString("COLUMN_NAME");
                            columns.add(name);
                            details.add(Map.of("name", name, "type", Objects.toString(rs.getString("TYPE_NAME"), ""),
                                    "nullable", rs.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls));
                        }
                    }
                    var keys = primaryKeys(metadata, catalog, schema, table);
                    var item = new LinkedHashMap<String, Object>();
                    item.put("schema", schema); item.put("table", table); item.put("columns", columns);
                    item.put("columnDetails", details); item.put("primaryKeys", keys);
                    result.add(item);
                }
        }
        return result;
    }

    private List<Map<String, Object>> readMysqlCompatibleTables(java.sql.Connection connection, String catalog) throws Exception {
        var columnsByTable = new LinkedHashMap<String, List<String>>();
        var detailsByTable = new LinkedHashMap<String, List<Map<String, Object>>>();
        var keysByTable = new LinkedHashMap<String, List<String>>();
        String sql = "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLUMN_KEY FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME,ORDINAL_POSITION";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, catalog);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME"), column = rs.getString("COLUMN_NAME");
                    columnsByTable.computeIfAbsent(table, ignored -> new ArrayList<>()).add(column);
                    detailsByTable.computeIfAbsent(table, ignored -> new ArrayList<>()).add(Map.of(
                            "name", column, "type", Objects.toString(rs.getString("DATA_TYPE"), ""),
                            "nullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))));
                    if ("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")))
                        keysByTable.computeIfAbsent(table, ignored -> new ArrayList<>()).add(column);
                }
            }
        }
        var result = new ArrayList<Map<String, Object>>();
        columnsByTable.forEach((table, columns) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("schema", null); item.put("table", table); item.put("columns", columns);
            item.put("columnDetails", detailsByTable.getOrDefault(table, List.of()));
            item.put("primaryKeys", keysByTable.getOrDefault(table, List.of()));
            result.add(item);
        });
        return result;
    }
    private List<String> primaryKeys(java.sql.DatabaseMetaData metadata, String catalog, String schema, String table) throws Exception {
        var ordered = new java.util.TreeMap<Short, String>();
        try (var rs = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }
    private String rootMessage(Throwable e) { var t = e; while (t.getCause() != null) t = t.getCause(); return t.getMessage(); }
    private String required(Map<String, Object> map, String key) { var value=map.get(key); if(value==null||value.toString().isBlank()) throw new IllegalArgumentException("Missing target setting: "+key); return value.toString(); }
}
