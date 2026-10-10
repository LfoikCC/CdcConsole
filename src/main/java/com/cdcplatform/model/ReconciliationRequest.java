package com.cdcplatform.model;

import com.cdcplatform.model.Types.ReconciliationMode;

import java.time.OffsetDateTime;
import java.util.List;

public record ReconciliationRequest(ReconciliationMode mode, List<Long> tableIds,
                                    OffsetDateTime startAt, OffsetDateTime endAt, Boolean drillDown) {
    public ReconciliationRequest {
        if (mode == null) mode = ReconciliationMode.INCREMENTAL;
        if (tableIds == null) tableIds = List.of();
        else tableIds = List.copyOf(tableIds);
        if (drillDown == null) drillDown = true;
        if (mode == ReconciliationMode.RANGE && (startAt == null || endAt == null || !startAt.isBefore(endAt)))
            throw new IllegalArgumentException("指定范围核对必须提供有效的开始和结束时间");
        if (mode == ReconciliationMode.FULL) { startAt = null; endAt = null; }
    }

    public static ReconciliationRequest incremental() {
        return new ReconciliationRequest(ReconciliationMode.INCREMENTAL, List.of(), null, null, true);
    }
}
