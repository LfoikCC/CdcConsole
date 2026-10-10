package com.cdcplatform.service.reconciliation;

import com.cdcplatform.model.Types.ReconciliationMetric;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class SqlServerReconciliationAdapter implements ReconciliationDatabaseAdapter {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    @Override public String id() { return "SQLSERVER"; }
    @Override public String quote(String value) {
        if (!IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException("非法标识符：" + value);
        return "[" + value + "]";
    }
    @Override public String aggregateExpression(ReconciliationMetric metric, String column) {
        return switch (metric) {
            case SUM -> "SUM(" + column + ")";
            case MIN -> "MIN(" + column + ")";
            case MAX -> "MAX(" + column + ")";
            case NULL_RATE -> "SUM(CASE WHEN " + column + " IS NULL THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END)";
            case TRUE_COUNT -> "SUM(CASE WHEN " + column + " = 1 THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END)";
            case FALSE_COUNT -> "SUM(CASE WHEN " + column + " = 0 THEN CAST(1 AS BIGINT) ELSE CAST(0 AS BIGINT) END)";
            case ROW_COUNT -> "COUNT_BIG(*)";
        };
    }
}
