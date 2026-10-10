package com.cdcplatform.repository;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ConfigRepository {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper json;

    public ConfigRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = jdbc; this.jdbcTemplate = jdbcTemplate; this.json = json;
    }

    public List<SourceConfig> sources() {
        return jdbc.sql("SELECT * FROM cdc_source ORDER BY id").query((rs, n) -> new SourceConfig(
                rs.getLong("id"), rs.getString("name"), DatabaseType.valueOf(rs.getString("db_type")),
                rs.getString("host"), rs.getInt("port"), rs.getString("database_name"),
                rs.getString("username"), "", rs.getInt("poll_interval_ms"), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public SourceConfig source(long id, boolean includePassword) {
        return jdbc.sql("SELECT * FROM cdc_source WHERE id=:id").param("id", id).query((rs, n) -> new SourceConfig(
                rs.getLong("id"), rs.getString("name"), DatabaseType.valueOf(rs.getString("db_type")),
                rs.getString("host"), rs.getInt("port"), rs.getString("database_name"), rs.getString("username"),
                includePassword ? rs.getString("password") : "", rs.getInt("poll_interval_ms"), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class))).optional().orElseThrow(() -> notFound("source", id));
    }

    public long addSource(SourceConfig s) {
        long id = nextId();
        jdbc.sql("INSERT INTO cdc_source(id,name,db_type,host,port,database_name,username,password,poll_interval_ms,enabled) " +
                        "VALUES(:id,:name,:type,:host,:port,:db,:user,:password,:interval,:enabled)")
                .param("id", id).param("name", s.name()).param("type", s.dbType().name()).param("host", s.host())
                .param("port", s.port()).param("db", s.databaseName()).param("user", s.username())
                .param("password", s.password()).param("interval", s.pollIntervalMs()).param("enabled", s.enabled()).update();
        return id;
    }

    public void updateSource(long id, SourceConfig s) {
        var current = source(id, true);
        ensureConnectionsEditable("source_id", id);
        String password = s.password() == null || s.password().isBlank() ? current.password() : s.password();
        jdbc.sql("UPDATE cdc_source SET name=:name,db_type=:type,host=:host,port=:port,database_name=:db," +
                        "username=:user,password=:password,poll_interval_ms=:interval,enabled=:enabled WHERE id=:id")
                .param("id", id).param("name", s.name()).param("type", s.dbType().name()).param("host", s.host())
                .param("port", s.port()).param("db", s.databaseName()).param("user", s.username())
                .param("password", password).param("interval", s.pollIntervalMs()).param("enabled", s.enabled()).update();
    }

    public List<TargetConfig> targets() {
        return jdbc.sql("SELECT * FROM cdc_target ORDER BY id").query((rs, n) -> new TargetConfig(
                rs.getLong("id"), rs.getString("name"), TargetType.valueOf(rs.getString("target_type")),
                mask(readMap(rs.getString("config_json"))), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public TargetConfig target(long id, boolean includeSecrets) {
        return jdbc.sql("SELECT * FROM cdc_target WHERE id=:id").param("id", id).query((rs, n) -> new TargetConfig(
                rs.getLong("id"), rs.getString("name"), TargetType.valueOf(rs.getString("target_type")),
                includeSecrets ? readMap(rs.getString("config_json")) : mask(readMap(rs.getString("config_json"))),
                rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> notFound("target", id));
    }

    public long addTarget(TargetConfig t) {
        long id = nextId();
        jdbc.sql("INSERT INTO cdc_target(id,name,target_type,config_json,enabled) VALUES(:id,:name,:type,:config,:enabled)")
                .param("id", id).param("name", t.name()).param("type", t.targetType().name()).param("config", write(t.config()))
                .param("enabled", t.enabled()).update();
        return id;
    }

    public void updateTarget(long id, TargetConfig t) {
        var current = target(id, true);
        ensureConnectionsEditable("target_id", id);
        var config = new java.util.HashMap<>(t.config());
        for (var key : List.of("password", "saslPassword")) {
            if (!config.containsKey(key) || config.get(key) == null || config.get(key).toString().isBlank() || "******".equals(config.get(key)))
                if (current.config().containsKey(key)) config.put(key, current.config().get(key));
        }
        jdbc.sql("UPDATE cdc_target SET name=:name,target_type=:type,config_json=:config,enabled=:enabled WHERE id=:id")
                .param("id", id).param("name", t.name()).param("type", t.targetType().name())
                .param("config", write(config)).param("enabled", t.enabled()).update();
    }

    public List<TableMapping> mappings() {
        return jdbc.sql("SELECT * FROM table_mapping ORDER BY id").query((rs, n) -> new TableMapping(
                rs.getLong("id"), rs.getLong("source_id"), rs.getLong("target_id"),
                rs.getString("source_database"), rs.getString("source_schema"), rs.getString("source_table"), rs.getString("target_schema"),
                rs.getString("target_table"), readStringMap(rs.getString("columns_json")),
                rs.getBoolean("include_operation"), rs.getBoolean("include_captured_at"),
                rs.getString("operation_column"), rs.getString("captured_at_column"), rs.getString("captured_at_format"),
                readStringMap(rs.getString("operation_values_json")),
                rs.getBoolean("enabled"))).list();
    }

    public TableMapping mapping(long id) {
        return mappings().stream().filter(m -> m.id() == id).findFirst().orElseThrow(() -> notFound("mapping", id));
    }

    public long addMapping(TableMapping m) {
        validateMapping(m);
        source(m.sourceId(), false); target(m.targetId(), false);
        long id = nextId();
        jdbc.sql("INSERT INTO table_mapping(id,source_id,target_id,source_database,source_schema,source_table,target_schema,target_table," +
                        "columns_json,include_operation,include_captured_at,operation_column,captured_at_column,captured_at_format,operation_values_json,enabled) " +
                        "VALUES(:id,:source,:target,:sd,:ss,:st,:ts,:tt,:columns,:operation,:captured,:operationColumn,:capturedColumn,:capturedFormat,:operationValues,:enabled)")
                .param("id", id).param("source", m.sourceId()).param("target", m.targetId()).param("sd", m.sourceDatabase()).param("ss", m.sourceSchema())
                .param("st", m.sourceTable()).param("ts", m.targetSchema()).param("tt", m.targetTable())
                .param("columns", write(m.columns())).param("operation", m.includeOperation())
                .param("captured", m.includeCapturedAt()).param("operationColumn", m.operationColumn())
                .param("capturedColumn", m.capturedAtColumn()).param("capturedFormat", m.capturedAtFormat())
                .param("operationValues", write(m.operationValues())).param("enabled", m.enabled())
                .update();
        return id;
    }

    public void updateMapping(long id, TableMapping m) {
        validateMapping(m);
        source(m.sourceId(), false); target(m.targetId(), false); mapping(id);
        jdbc.sql("UPDATE table_mapping SET source_id=:source,target_id=:target,source_database=:sd,source_schema=:ss,source_table=:st," +
                        "target_schema=:ts,target_table=:tt,columns_json=:columns,include_operation=:operation," +
                        "include_captured_at=:captured,operation_column=:operationColumn,captured_at_column=:capturedColumn," +
                        "captured_at_format=:capturedFormat,operation_values_json=:operationValues,enabled=:enabled WHERE id=:id")
                .param("id", id).param("source", m.sourceId()).param("target", m.targetId()).param("sd", m.sourceDatabase()).param("ss", m.sourceSchema())
                .param("st", m.sourceTable()).param("ts", m.targetSchema()).param("tt", m.targetTable())
                .param("columns", write(m.columns())).param("operation", m.includeOperation())
                .param("captured", m.includeCapturedAt()).param("operationColumn", m.operationColumn())
                .param("capturedColumn", m.capturedAtColumn()).param("capturedFormat", m.capturedAtFormat())
                .param("operationValues", write(m.operationValues())).param("enabled", m.enabled()).update();
        clearCheckpoint(id);
    }

    public void setMappingEnabled(long id, boolean enabled) {
        mapping(id);
        jdbc.sql("UPDATE table_mapping SET enabled=:enabled WHERE id=:id").param("id", id).param("enabled", enabled).update();
    }

    public int setAllMappingsEnabled(boolean enabled) {
        return jdbc.sql("UPDATE table_mapping SET enabled=:enabled").param("enabled", enabled).update();
    }

    public String checkpoint(long mappingId) {
        return jdbc.sql("SELECT last_lsn FROM cdc_checkpoint WHERE mapping_id=:id").param("id", mappingId)
                .query(String.class).optional().orElse(null);
    }

    public void saveCheckpoint(long mappingId, String lsn) {
        int changed = jdbc.sql("UPDATE cdc_checkpoint SET last_lsn=:lsn,updated_at=CURRENT_TIMESTAMP WHERE mapping_id=:id")
                .param("id", mappingId).param("lsn", lsn).update();
        if (changed == 0) jdbc.sql("INSERT INTO cdc_checkpoint(mapping_id,last_lsn) VALUES(:id,:lsn)")
                .param("id", mappingId).param("lsn", lsn).update();
    }

    public void clearCheckpoint(long mappingId) {
        jdbc.sql("DELETE FROM cdc_checkpoint WHERE mapping_id=:id").param("id", mappingId).update();
    }

    public Map<String, String> snapshot(long mappingId) {
        var rows = new LinkedHashMap<String, String>();
        jdbc.sql("SELECT row_key,row_json FROM cdc_snapshot WHERE mapping_id=:id ORDER BY row_key")
                .param("id", mappingId).query((rs, n) -> {
                    rows.put(rs.getString("row_key"), rs.getString("row_json"));
                    return 0;
                }).list();
        return rows;
    }

    @Transactional
    public void replaceSnapshot(long mappingId, Map<String, String> rows) {
        jdbc.sql("DELETE FROM cdc_snapshot WHERE mapping_id=:id").param("id", mappingId).update();
        rows.forEach((key, value) -> jdbc.sql("INSERT INTO cdc_snapshot(mapping_id,row_key,row_json) VALUES(:id,:key,:json)")
                .param("id", mappingId).param("key", key).param("json", value).update());
    }

    public void clearSnapshot(long mappingId) {
        jdbc.sql("DELETE FROM cdc_snapshot WHERE mapping_id=:id").param("id", mappingId).update();
    }

    public void beginSnapshotStage(long mappingId, long generation) {
        jdbc.sql("DELETE FROM cdc_snapshot_stage WHERE mapping_id=:id").param("id", mappingId).update();
    }

    public void appendSnapshotStage(long mappingId, long generation, Map<String, String> rows) {
        if (rows.isEmpty()) return;
        jdbcTemplate.batchUpdate("INSERT INTO cdc_snapshot_stage(mapping_id,generation,row_key,row_json) VALUES(?,?,?,?)",
                rows.entrySet(), rows.size(), (statement, entry) -> {
                    statement.setLong(1, mappingId);
                    statement.setLong(2, generation);
                    statement.setString(3, entry.getKey());
                    statement.setString(4, entry.getValue());
                });
    }

    public void visitSnapshotStage(long mappingId, long generation, String sql,
                                   org.springframework.jdbc.core.RowCallbackHandler handler) {
        String positional = sql.replace(":generation", "?").replace(":id", "?");
        jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(positional);
            int index = 1;
            for (int cursor = 0; cursor < sql.length();) {
                int generationAt = sql.indexOf(":generation", cursor);
                int idAt = sql.indexOf(":id", cursor);
                if (generationAt < 0 && idAt < 0) break;
                if (generationAt >= 0 && (idAt < 0 || generationAt < idAt)) {
                    statement.setLong(index++, generation); cursor = generationAt + 11;
                } else {
                    statement.setLong(index++, mappingId); cursor = idAt + 3;
                }
            }
            statement.setFetchSize(1000);
            return statement;
        }, handler);
    }

    @Transactional
    public void promoteSnapshotStage(long mappingId, long generation) {
        jdbc.sql("DELETE FROM cdc_snapshot WHERE mapping_id=:id").param("id", mappingId).update();
        jdbc.sql("INSERT INTO cdc_snapshot(mapping_id,row_key,row_json) SELECT mapping_id,row_key,row_json FROM cdc_snapshot_stage WHERE mapping_id=:id AND generation=:generation")
                .param("id", mappingId).param("generation", generation).update();
        jdbc.sql("DELETE FROM cdc_snapshot_stage WHERE mapping_id=:id").param("id", mappingId).update();
    }

    @Transactional
    public void deleteSource(long id) {
        source(id, false);
        long tasks = jdbc.sql("SELECT COUNT(*) FROM sync_task WHERE source_id=:id").param("id", id).query(Long.class).single();
        if (tasks > 0) throw new IllegalStateException("该源连接仍被 " + tasks + " 个同步任务使用，请先删除相关任务");
        jdbc.sql("DELETE FROM table_mapping WHERE source_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM cdc_source WHERE id=:id").param("id", id).update();
    }

    @Transactional
    public void deleteTarget(long id) {
        target(id, false);
        long tasks = jdbc.sql("SELECT COUNT(*) FROM sync_task WHERE target_id=:id").param("id", id).query(Long.class).single();
        if (tasks > 0) throw new IllegalStateException("该目标连接仍被 " + tasks + " 个同步任务使用，请先删除相关任务");
        jdbc.sql("DELETE FROM table_mapping WHERE target_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM cdc_target WHERE id=:id").param("id", id).update();
    }

    @Transactional
    public void deleteMapping(long id) {
        mapping(id);
        jdbc.sql("DELETE FROM table_mapping WHERE id=:id").param("id", id).update();
    }

    private long nextId() { return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single(); }
    private void ensureConnectionsEditable(String column, long id) {
        long active = jdbc.sql("SELECT COUNT(*) FROM sync_task WHERE " + column + "=:id AND status NOT IN ('DRAFT','STOPPED')")
                .param("id", id).query(Long.class).single();
        if (active > 0) throw new IllegalStateException("连接被运行中的同步任务使用，请先停止相关任务");
    }
    private void validateMapping(TableMapping mapping) {
        if (mapping.includeOperation() && !IDENTIFIER.matcher(mapping.operationColumn()).matches())
            throw new IllegalArgumentException("Invalid CDC operation target column");
        if (mapping.includeCapturedAt() && !IDENTIFIER.matcher(mapping.capturedAtColumn()).matches())
            throw new IllegalArgumentException("Invalid CDC captured-at target column");
        if (mapping.includeCapturedAt() && !"TIMESTAMP".equalsIgnoreCase(mapping.capturedAtFormat()) && !"ISO8601".equalsIgnoreCase(mapping.capturedAtFormat()))
            try { DateTimeFormatter.ofPattern(mapping.capturedAtFormat()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid captured-at time format: " + mapping.capturedAtFormat()); }
    }
    private Map<String, Object> readMap(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private Map<String, String> readStringMap(String value) { try { return json.readValue(value, new TypeReference<>() {}); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid JSON", e); } }
    private Map<String, Object> mask(Map<String, Object> config) { var copy = new java.util.HashMap<>(config); for (var key : List.of("password", "saslPassword")) if (copy.containsKey(key)) copy.put(key, "******"); return copy; }
    private IllegalArgumentException notFound(String type, long id) { return new IllegalArgumentException(type + " " + id + " not found"); }
}
