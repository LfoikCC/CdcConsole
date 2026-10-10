package com.cdcplatform.service;

import com.cdcplatform.model.Types.ReconciliationMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationServiceTest {
    @Test
    void fullRunsAreNeverReusedButWindowRunsRemainIdempotent() {
        assertThat(ReconciliationService.isWindowIdempotent(ReconciliationMode.FULL)).isFalse();
        assertThat(ReconciliationService.isWindowIdempotent(ReconciliationMode.INCREMENTAL)).isTrue();
        assertThat(ReconciliationService.isWindowIdempotent(ReconciliationMode.RANGE)).isTrue();
    }
}
