package com.cdcplatform.model.dev;

import java.time.OffsetDateTime;

/** 一次数据流运行。{@code graphSnapshot} 保留当时的图，事后可复现。 */
public record DevFlowRun(long id, long flowId, int flowVersion, String status, String triggerType,
                         String actor, OffsetDateTime startedAt, OffsetDateTime endedAt, Long durationMs,
                         int nodeTotal, int nodeSucceeded, long rowsWritten, String message) {
}
