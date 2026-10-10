package com.cdcplatform.service.delivery;

import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqlServerBatchWriter extends AbstractJdbcBatchWriter {
    public SqlServerBatchWriter(TargetConfig target, TableMapping mapping, SyncTaskTable table, DeliveryPayloadMapper mapper) {
        super(target, mapping, table, mapper);
    }

    @Override protected String jdbcUrl(Map<String, Object> c) {
        int port = Integer.parseInt(String.valueOf(c.getOrDefault("port", 1433)));
        return "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true".formatted(
                required(c, "host"), port, required(c, "database"));
    }

    @Override protected StatementSpec upsertSpec(Map<String, Object> row, List<String> keys) {
        var columns = new ArrayList<>(row.keySet());
        var updates = columns.stream().filter(column -> !keys.contains(column)).toList();
        String source = String.join(",", columns.stream().map(column -> "? AS " + quote(column)).toList());
        String on = String.join(" AND ", keys.stream().map(key -> "target." + quote(key) + "=source." + quote(key)).toList());
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(qualifiedTable()).append(" WITH (HOLDLOCK) AS target USING (SELECT ")
                .append(source).append(") AS source ON ").append(on).append(' ');
        if (!updates.isEmpty()) sql.append("WHEN MATCHED THEN UPDATE SET ").append(String.join(",",
                updates.stream().map(column -> "target." + quote(column) + "=source." + quote(column)).toList())).append(' ');
        sql.append("WHEN NOT MATCHED THEN INSERT (").append(String.join(",", columns.stream().map(this::quote).toList()))
                .append(") VALUES (").append(String.join(",", columns.stream().map(column -> "source." + quote(column)).toList()))
                .append(");");
        return new StatementSpec(sql.toString(), columns);
    }

    @Override protected String quote(String identifier) { return "[" + checked(identifier) + "]"; }
}
