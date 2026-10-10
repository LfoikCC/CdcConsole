package com.cdcplatform.model;

import com.cdcplatform.model.Types.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public record SyncTask(
        Long id,
        @NotBlank String name,
        String description,
        @NotNull Long sourceId,
        @NotNull Long targetId,
        @NotNull SyncMode syncMode,
        DdlPolicy ddlPolicy,
        String timeZone,
        TaskStatus status,
        Integer version,
        Integer alertLagSeconds,
        String reconciliationCron,
        Integer reconciliationDelayMinutes,
        Integer reconciliationWindowMinutes,
        Boolean reconcileAfterSnapshot,
        @Valid @NotEmpty List<SyncTaskTable> tables,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public SyncTask {
        if (description == null) description = "";
        if (ddlPolicy == null) ddlPolicy = DdlPolicy.DETECT_AND_BLOCK;
        if (timeZone == null || timeZone.isBlank()) timeZone = "Asia/Shanghai";
        if (status == null) status = TaskStatus.DRAFT;
        if (version == null) version = 0;
        if (alertLagSeconds == null || alertLagSeconds < 1) alertLagSeconds = 60;
        if (reconciliationDelayMinutes == null || reconciliationDelayMinutes < 0) reconciliationDelayMinutes = 120;
        if (reconciliationWindowMinutes == null || reconciliationWindowMinutes < 1) reconciliationWindowMinutes = 60;
        if (reconcileAfterSnapshot == null) reconcileAfterSnapshot = true;
        if (tables == null) tables = List.of();
    }

    public SyncTask(Long id, String name, String description, Long sourceId, Long targetId, SyncMode syncMode,
                    DdlPolicy ddlPolicy, String timeZone, TaskStatus status, Integer version, Integer alertLagSeconds,
                    String reconciliationCron, List<SyncTaskTable> tables, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, name, description, sourceId, targetId, syncMode, ddlPolicy, timeZone, status, version,
                alertLagSeconds, reconciliationCron, 120, 60, true, tables, createdAt, updatedAt);
    }
}
