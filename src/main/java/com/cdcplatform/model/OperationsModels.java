package com.cdcplatform.model;

import com.cdcplatform.model.Types.AlertStatus;
import com.cdcplatform.model.Types.AlertType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class OperationsModels {
    private OperationsModels() {}

    public record TaskInstance(long id, long taskId, String status, OffsetDateTime startedAt,
                               OffsetDateTime endedAt, String message) {}
    public record MetricSample(long id, long taskId, OffsetDateTime sampledAt, long processedRows,
                               long errorCount, long lagMs, long queueDepth, double readRate, double writeRate) {}
    public record RuntimeLog(long id, long taskId, Long tableId, String level, String message,
                             OffsetDateTime createdAt) {}
    public record AlertEvent(long id, long taskId, Long tableId, AlertType type, AlertStatus status,
                             String title, String message, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record ReconciliationRun(long id, long taskId, String status, String mode, String triggerSource,
                                    OffsetDateTime windowStart, OffsetDateTime windowEnd,
                                    long sourceRows, long targetRows, long mismatchCount,
                                    List<String> sampleKeys, String message,
                                    OffsetDateTime startedAt, OffsetDateTime endedAt) {}
    public record ReconciliationDetail(long id, long runId, long tableId, String status,
                                       long sourceRows, long targetRows, long mismatchCount,
                                       long targetMissing, long targetRedundant, long fieldMismatch,
                                       long deleteResidue, String message, List<ReconciliationFieldResult> fields) {}
    public record ReconciliationFieldResult(long id, long runId, long tableId, String sourceColumn,
                                            String targetColumn, String logicalType, String metric,
                                            String sourceValue, String targetValue, boolean matched) {}
    public record ReconciliationDiscrepancy(long id, long runId, long tableId, String type,
                                            Map<String, Object> key, String fieldName,
                                            Object sourceValue, Object targetValue, OffsetDateTime createdAt) {}
}
