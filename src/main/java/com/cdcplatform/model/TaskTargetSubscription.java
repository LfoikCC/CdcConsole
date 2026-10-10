package com.cdcplatform.model;

import java.time.OffsetDateTime;
import java.util.List;

/** User-visible target under one source-side sync task. */
public record TaskTargetSubscription(long id, long taskId, long targetId, String name, String description,
                                     String status, String initializationStatus, String lastError,
                                     long pendingEvents, long pendingBytes,
                                     OffsetDateTime initializedAt, OffsetDateTime createdAt,
                                     OffsetDateTime updatedAt, List<TaskTargetSubscriptionTable> tables) { }
