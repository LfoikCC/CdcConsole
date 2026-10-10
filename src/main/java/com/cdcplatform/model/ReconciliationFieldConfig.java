package com.cdcplatform.model;

import com.cdcplatform.model.Types.ReconciliationLogicalType;
import com.cdcplatform.model.Types.ReconciliationMetric;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReconciliationFieldConfig(
        @NotBlank String sourceColumn,
        @NotBlank String targetColumn,
        @NotNull ReconciliationLogicalType logicalType,
        List<ReconciliationMetric> metrics) {

    public ReconciliationFieldConfig {
        if (metrics == null || metrics.isEmpty()) metrics = defaultMetrics(logicalType);
        else metrics = List.copyOf(metrics);
    }

    public static List<ReconciliationMetric> defaultMetrics(ReconciliationLogicalType type) {
        return switch (type == null ? ReconciliationLogicalType.OTHER : type) {
            case NUMBER -> List.of(ReconciliationMetric.SUM);
            case STRING, OTHER -> List.of(ReconciliationMetric.NULL_RATE);
            case DATETIME -> List.of(ReconciliationMetric.MIN, ReconciliationMetric.MAX);
            case BOOLEAN -> List.of(ReconciliationMetric.TRUE_COUNT, ReconciliationMetric.FALSE_COUNT);
        };
    }
}
