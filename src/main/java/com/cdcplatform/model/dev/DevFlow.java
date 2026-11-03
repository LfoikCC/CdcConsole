package com.cdcplatform.model.dev;

import java.time.OffsetDateTime;

/** 一条数据流。图本体在 {@code graphJson} 里，见 {@link DevGraph}。 */
public record DevFlow(long id, String name, String description, String status, String graphJson,
                      int version, String lastRunStatus, OffsetDateTime lastRunAt, long lastRunRows,
                      String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt, Long folderId) {
}
