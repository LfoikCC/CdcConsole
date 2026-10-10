package com.cdcplatform.service.reconciliation;

import com.cdcplatform.model.Types.ReconciliationMetric;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Pattern;

@Component
public class MySqlReconciliationAdapter implements ReconciliationDatabaseAdapter {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    @Override public String id() { return "MYSQL"; }
    @Override public String quote(String value) {
        if (!IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException("非法标识符：" + value);
        return "`" + value + "`";
    }
    @Override public String aggregateExpression(ReconciliationMetric metric, String column) {
        return switch (metric) {
            case SUM -> "SUM(" + column + ")";
            case MIN -> "MIN(" + column + ")";
            case MAX -> "MAX(" + column + ")";
            case NULL_RATE -> "SUM(CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END)";
            case TRUE_COUNT -> "SUM(CASE WHEN " + column + " = 1 THEN 1 ELSE 0 END)";
            case FALSE_COUNT -> "SUM(CASE WHEN " + column + " = 0 THEN 1 ELSE 0 END)";
            case ROW_COUNT -> "COUNT(*)";
        };
    }
    @Override public void configureStreaming(PreparedStatement statement) throws SQLException {
        statement.setFetchSize(Integer.MIN_VALUE);
        statement.setQueryTimeout(120);
    }
}
