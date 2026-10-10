package com.cdcplatform.model;

import java.time.OffsetDateTime;

/** Target-specific mapping for exactly one source table in a task. */
public record TaskTargetSubscriptionTable(long id, long subscriptionId, long sourceTaskTableId,
                                          long runtimeTaskTableId, Long captureGroupId, long mappingId,
                                          String status, String initializationError,
                                          OffsetDateTime initializationStartedAt,
                                          OffsetDateTime initializationCompletedAt,
                                          SyncTaskTable mapping) { }
