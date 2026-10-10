package com.cdcplatform.service.reconciliation;

import com.cdcplatform.model.Types.ReconciliationMetric;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;

public interface ReconciliationDatabaseAdapter {
    String id();
    String quote(String identifier);
    default String qualified(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }
    String aggregateExpression(ReconciliationMetric metric, String quotedColumn);
    default int batchSize(int keyColumns) { return Math.max(1, Math.min(1000, 1900 / Math.max(1, keyColumns))); }
    default void configureStreaming(PreparedStatement statement) throws SQLException {
        statement.setFetchSize(1000);
        statement.setQueryTimeout(120);
    }
    default void bindTime(PreparedStatement statement, int index, OffsetDateTime value) throws SQLException {
        statement.setObject(index, value.toLocalDateTime());
    }
    default String visiblePredicate() { return "1=1"; }
}
