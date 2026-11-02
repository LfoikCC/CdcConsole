package com.cdcplatform.repository;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.service.account.AccountAdapter;
import com.cdcplatform.service.account.AccountCapabilities;
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
    private final AccountRepository accounts;
    private final AccountAdapter adapter = new AccountAdapter();

    public ConfigRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = jdbc; this.jdbcTemplate = jdbcTemplate; this.json = json;
        this.accounts = new AccountRepository(jdbc, json);
    }

    public List<SourceConfig> sources() {
        return accounts.accountsByCapability(Capability.SOURCE, false).stream()
                .map(adapter::toSource).toList();
    }

    public SourceConfig source(long id, boolean includePassword) {
        return adapter.toSource(accounts.account(id, includePassword));
    }

    public long addSource(SourceConfig s) {
        AccountCapabilities.require(adapter.accountType(s.dbType()), Capability.SOURCE, "作为同步源");
        return accounts.add(adapter.fromSource(s));
    }

    public void updateSource(long id, SourceConfig s) {
        requireEditable(id);
        AccountCapabilities.require(adapter.accountType(s.dbType()), Capability.SOURCE, "作为同步源");
        accounts.update(id, adapter.fromSource(s));
    }

    public List<TargetConfig> targets() {
        return accounts.accountsByCapability(Capability.TARGET, false).stream()
                .map(account -> adapter.toTarget(account, false)).toList();
    }

    public TargetConfig target(long id, boolean includeSecrets) {
        return adapter.toTarget(accounts.account(id, includeSecrets), includeSecrets);
    }

    public long addTarget(TargetConfig t) {
        Account account = adapter.fromTarget(t);
        AccountCapabilities.require(account.accountType(), Capability.TARGET, "作为同步目标");
        return accounts.add(account);
    }

    public void updateTarget(long id, TargetConfig t) {
        requireEditable(id);
        Account account = adapter.fromTarget(t);
        AccountCapabilities.require(account.accountType(), Capability.TARGET, "作为同步目标");
        accounts.update(id, account);
    }

    public List<Account> accounts() {
        return accounts.accounts(false);
    }

    public Account account(long id, boolean includeSecrets) {
        return accounts.account(id, includeSecrets);
    }

    public long addAccount(Account account) {
        return accounts.add(account);
    }

    public void updateAccount(long id, Account account) {
        requireEditable(id);
        accounts.update(id, account);
    }

    public void deleteAccount(long id) {
        accounts.delete(id);
    }

    public Map<String, Long> accountUsage(long id) {
        return accounts.usage(id);
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
        accounts.delete(id);
    }

    @Transactional
    public void deleteTarget(long id) {
        accounts.delete(id);
    }

    @Transactional
    public void deleteMapping(long id) {
        mapping(id);
        jdbc.sql("DELETE FROM table_mapping WHERE id=:id").param("id", id).update();
    }

    private long nextId() { return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single(); }
    private void requireEditable(long id) {
        long active = accounts.activeTasks(id);
        if (active > 0) throw new IllegalStateException("账户被运行中的同步任务使用，请先停止相关任务");
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
