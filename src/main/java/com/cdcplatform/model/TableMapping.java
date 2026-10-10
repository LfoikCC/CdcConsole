package com.cdcplatform.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record TableMapping(Long id, @NotNull Long sourceId, @NotNull Long targetId,
                           String sourceDatabase,
                           String sourceSchema, @NotBlank String sourceTable,
                           String targetSchema, @NotBlank String targetTable,
                           @NotNull Map<String, String> columns,
                           boolean includeOperation, boolean includeCapturedAt,
                           String operationColumn, String capturedAtColumn, String capturedAtFormat,
                           Map<String, String> operationValues,
                           boolean enabled) {
    public TableMapping {
        if (operationColumn == null || operationColumn.isBlank()) operationColumn = "_cdc_operation";
        if (capturedAtColumn == null || capturedAtColumn.isBlank()) capturedAtColumn = "_cdc_captured_at";
        if (capturedAtFormat == null || capturedAtFormat.isBlank()) capturedAtFormat = "TIMESTAMP";
        if (operationValues == null) operationValues = Map.of();
    }

    public TableMapping(Long id, Long sourceId, Long targetId,
                        String sourceSchema, String sourceTable, String targetSchema, String targetTable,
                        Map<String, String> columns, boolean includeOperation, boolean includeCapturedAt,
                        String operationColumn, String capturedAtColumn, String capturedAtFormat,
                        Map<String, String> operationValues, boolean enabled) {
        this(id, sourceId, targetId, null, sourceSchema, sourceTable, targetSchema, targetTable, columns,
                includeOperation, includeCapturedAt, operationColumn, capturedAtColumn, capturedAtFormat,
                operationValues, enabled);
    }
}
