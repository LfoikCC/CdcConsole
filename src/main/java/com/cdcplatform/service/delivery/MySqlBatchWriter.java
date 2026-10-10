package com.cdcplatform.service.delivery;

import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MySqlBatchWriter extends AbstractJdbcBatchWriter {
    public MySqlBatchWriter(TargetConfig target, TableMapping mapping, SyncTaskTable table, DeliveryPayloadMapper mapper) {
        super(target, mapping, table, mapper);
    }

    @Override protected String jdbcUrl(Map<String, Object> c) {
        int port = Integer.parseInt(String.valueOf(c.getOrDefault("port", 3306)));
        return "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true".formatted(
                required(c, "host"), port, required(c, "database"));
    }

    @Override protected StatementSpec upsertSpec(Map<String, Object> row, List<String> keys) {
        var columns = new ArrayList<>(row.keySet());
        var updates = columns.stream().filter(column -> !keys.contains(column)).toList();
        String update = updates.isEmpty() ? quote(keys.getFirst()) + "=" + quote(keys.getFirst()) : String.join(",",
                updates.stream().map(column -> quote(column) + "=VALUES(" + quote(column) + ")").toList());
        String sql = "INSERT INTO " + qualifiedTable() + " (" + String.join(",", columns.stream().map(this::quote).toList()) +
                ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ") ON DUPLICATE KEY UPDATE " + update;
        return new StatementSpec(sql, columns);
    }

    @Override protected String quote(String identifier) { return "`" + checked(identifier) + "`"; }
}
