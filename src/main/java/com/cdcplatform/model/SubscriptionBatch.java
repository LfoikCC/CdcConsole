package com.cdcplatform.model;

import java.time.OffsetDateTime;

public record SubscriptionBatch(String subscriptionBatchId, long subscriptionId, String captureBatchId,
                                String status, int attemptCount, OffsetDateTime nextRetryAt,
                                String lastError, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                OffsetDateTime deliveredAt) { }
