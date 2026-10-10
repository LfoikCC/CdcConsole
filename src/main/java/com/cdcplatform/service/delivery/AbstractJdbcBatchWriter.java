package com.cdcplatform.service.delivery;

import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.DeliveryBatch;
import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.Operation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

abstract class AbstractJdbcBatchWriter implements TargetBatchWriter {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    protected final TargetConfig target;
    protected final TableMapping mapping;
    protected final SyncTaskTable table;
    protected final DeliveryPayloadMapper mapper;
    private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();
    private Connection connection;

    AbstractJdbcBatchWriter(TargetConfig target, TableMapping mapping, SyncTaskTable table, DeliveryPayloadMapper mapper) {
        this.target = target; this.mapping = mapping; this.table = table; this.mapper = mapper;
    }

    @Override
    public synchronized BatchWriteResult write(DeliveryBatch batch, List<ChangeEvent> events) throws Exception {
        Connection c = connection();
        try {
            StatementRun run = null;
            for (var event : events) {
                var row = mapper.transform(event, mapping);
                var keys = mapper.targetKeys(mapping, table, row);
                StatementSpec spec = event.operation() == Operation.DELETE ? deleteSpec(row, keys) : upsertSpec(row, keys);
                if (run == null || !run.spec.sql.equals(spec.sql)) {
                    if (run != null) execute(run);
                    run = new StatementRun(spec);
                }
                run.rows.add(row);
            }
            if (run != null) execute(run);
            c.commit();
            return new BatchWriteResult(events.size(), 1);
        } catch (Exception e) {
            try { c.rollback(); } catch (Exception ignored) {}
            if (connectionProblem(e)) invalidate();
            throw e;
        }
    }

    protected abstract String jdbcUrl(Map<String, Object> config);
    protected abstract StatementSpec upsertSpec(Map<String, Object> row, List<String> keys);
    protected abstract String quote(String identifier);

    protected StatementSpec deleteSpec(Map<String, Object> row, List<String> keys) {
        String sql = "DELETE FROM " + qualifiedTable() + " WHERE " + String.join(" AND ",
                keys.stream().map(key -> quote(key) + "=?").toList());
        return new StatementSpec(sql, keys);
    }

    protected String qualifiedTable() {
        String name = quote(mapping.targetTable());
        return mapping.targetSchema() == null || mapping.targetSchema().isBlank() ? name : quote(mapping.targetSchema()) + "." + name;
    }

    protected String checked(String value) {
        if (!IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException("Invalid SQL identifier: " + value);
        return value;
    }

    private Connection connection() throws Exception {
        if (connection != null && !connection.isClosed() && connection.isValid(3)) return connection;
        invalidate();
        var config = target.config();
        connection = DriverManager.getConnection(jdbcUrl(config), required(config, "username"), required(config, "password"));
        connection.setAutoCommit(false);
        return connection;
    }

    private void execute(StatementRun run) throws Exception {
        PreparedStatement statement = statements.get(run.spec.sql);
        if (statement == null || statement.isClosed()) {
            statement = connection.prepareStatement(run.spec.sql);
            statement.setQueryTimeout(120);
            statements.put(run.spec.sql, statement);
        }
        statement.clearBatch();
        for (var row : run.rows) {
            for (int i = 0; i < run.spec.bindings.size(); i++) statement.setObject(i + 1, row.get(run.spec.bindings.get(i)));
            statement.addBatch();
        }
        statement.executeBatch();
    }

    private boolean connectionProblem(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.sql.SQLRecoverableException || current instanceof java.sql.SQLTransientConnectionException) return true;
            if (current instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) return true;
            current = current.getCause();
        }
        return false;
    }

    protected String required(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing target setting: " + key);
        return value.toString();
    }

    private void invalidate() {
        statements.values().forEach(statement -> { try { statement.close(); } catch (Exception ignored) {} });
        statements.clear();
        if (connection != null) try { connection.close(); } catch (Exception ignored) {}
        connection = null;
    }

    @Override public synchronized void close() { invalidate(); }

    protected record StatementSpec(String sql, List<String> bindings) {}
    private static final class StatementRun {
        private final StatementSpec spec;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private StatementRun(StatementSpec spec) { this.spec = spec; }
    }
}
