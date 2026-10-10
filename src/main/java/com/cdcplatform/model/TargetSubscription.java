package com.cdcplatform.model;

import java.time.OffsetDateTime;

public record TargetSubscription(long id, long captureGroupId, Long mappingId, long targetId,
                                 String targetSchema, String targetTable, String mappingOverrideJson,
                                 long mappingVersion, String status, String deliveryPositionJson,
                                 long maxPendingBytes, long pendingBytes, long pendingEvents,
                                 String lastError, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
