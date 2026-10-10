package com.cdcplatform.model;

import com.cdcplatform.model.Types.TargetType;

import java.time.OffsetDateTime;

public record DeliveryBatch(
        String batchId,
        long taskId,
        long taskTableId,
        long mappingId,
        long generation,
        TargetType targetType,
        String sourceStartPosition,
        String sourceEndPosition,
        String segmentPath,
        String checksum,
        int eventCount,
        long payloadBytes,
        BatchStatus status,
        int attemptCount,
        OffsetDateTime nextRetryAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deliveredAt) {

    public enum BatchStatus { STAGED, DELIVERING, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED }
}
