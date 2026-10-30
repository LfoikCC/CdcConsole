package com.cdcplatform.model.dev;

import java.time.OffsetDateTime;

/** 一次运行里单个节点的执行记录。{@code nodeKey} 对应图里的节点 id，不建外键。 */
public record DevNodeRun(long id, long runId, String nodeKey, String nodeType, String nodeName,
                         String status, OffsetDateTime startedAt, OffsetDateTime endedAt, Long durationMs,
                         long rowsRead, long rowsWritten, String message) {
}
