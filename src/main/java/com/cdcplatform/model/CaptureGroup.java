package com.cdcplatform.model;

import java.time.OffsetDateTime;

public record CaptureGroup(long id, long sourceId, String sourceDatabase, String sourceSchema,
                           String sourceTable, String syncMode, String canonicalSchemaJson,
                           String status, String capturePositionJson, long generation,
                           long maxPendingBytes, String lastError, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) { }
