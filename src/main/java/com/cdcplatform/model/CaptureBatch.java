package com.cdcplatform.model;

import java.time.OffsetDateTime;

public record CaptureBatch(String captureBatchId, long captureGroupId, long generation,
                           String sourceStartPosition, String sourceEndPosition, String segmentPath,
                           String checksum, int eventCount, long payloadBytes, String status,
                           OffsetDateTime createdAt) { }
