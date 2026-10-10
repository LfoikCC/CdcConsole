package com.cdcplatform.model;

import com.cdcplatform.model.Types.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record SyncTaskTable(
        Long id,
        Long mappingId,
        @NotBlank String sourceDatabase,
        String sourceSchema,
        @NotBlank String sourceTable,
        String targetSchema,
        @NotBlank String targetTable,
        @NotNull Map<String, String> columns,
        List<String> keyColumns,
        boolean includeOperation,
        boolean includeCapturedAt,
        String operationColumn,
        String capturedAtColumn,
        String capturedAtFormat,
        Map<String, String> operationValues,
        SnapshotProfile snapshotProfile,
        IncrementalProfile incrementalProfile,
        String structureFingerprint,
        @NotNull ReconciliationConfig reconciliation,
        TableStatus status,
        long processedRows,
        long errorCount,
        Long lastLatencyMs,
        OffsetDateTime lastEventAt,
        String lastError) {

    public SyncTaskTable {
        if (columns == null) columns = Map.of();
        if (keyColumns == null) keyColumns = List.of();
        if (operationColumn == null || operationColumn.isBlank()) operationColumn = "_cdc_operation";
        if (capturedAtColumn == null || capturedAtColumn.isBlank()) capturedAtColumn = "_cdc_captured_at";
        if (capturedAtFormat == null || capturedAtFormat.isBlank()) capturedAtFormat = "TIMESTAMP";
        if (operationValues == null) operationValues = Map.of();
        if (snapshotProfile == null) snapshotProfile = SnapshotProfile.LIGHT;
        if (incrementalProfile == null) incrementalProfile = IncrementalProfile.STEADY;
        if (reconciliation == null) reconciliation = ReconciliationConfig.disabled();
        if (status == null) status = TableStatus.DRAFT;
    }

    public SyncTaskTable(Long id, Long mappingId, String sourceDatabase, String sourceSchema, String sourceTable,
                         String targetSchema, String targetTable, Map<String, String> columns, List<String> keyColumns,
                         boolean includeOperation, boolean includeCapturedAt, String operationColumn,
                         String capturedAtColumn, String capturedAtFormat, Map<String, String> operationValues,
                         SnapshotProfile snapshotProfile, IncrementalProfile incrementalProfile,
                         String structureFingerprint, TableStatus status, long processedRows, long errorCount,
                         Long lastLatencyMs, OffsetDateTime lastEventAt, String lastError) {
        this(id, mappingId, sourceDatabase, sourceSchema, sourceTable, targetSchema, targetTable, columns, keyColumns,
                includeOperation, includeCapturedAt, operationColumn, capturedAtColumn, capturedAtFormat,
                operationValues, snapshotProfile, incrementalProfile, structureFingerprint,
                ReconciliationConfig.disabled(), status, processedRows, errorCount, lastLatencyMs, lastEventAt, lastError);
    }
}
