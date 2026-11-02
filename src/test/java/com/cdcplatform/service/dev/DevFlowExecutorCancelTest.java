package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import com.cdcplatform.model.dev.DevFlowRun;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DevFlowRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DevFlowExecutorCancelTest {

    private final DevFlowRepository flows = mock(DevFlowRepository.class);
    private final DevFlowExecutor executor = new DevFlowExecutor(flows, mock(ConfigRepository.class),
            mock(DevGraphValidator.class), mock(DevNodeReader.class), mock(DevOperatorSql.class),
            mock(DevTargetWriter.class), mock(DevDatasetReader.class), mock(DevTempTableStore.class),
            new DevProperties());

    @Test
    void rejectsCancellingAFinishedRun() {
        when(flows.run(1, 2)).thenReturn(new DevFlowRun(2, 1, 1, "SUCCEEDED", "MANUAL", "admin",
                null, null, 12L, 1, 1, 5, null));

        assertThatThrownBy(() -> executor.cancel(1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("运行已结束");
    }

    @Test
    void cancelsResidualRunningRunWhenProcessRestarted() {
        when(flows.run(1, 3)).thenReturn(new DevFlowRun(3, 1, 1, "RUNNING", "MANUAL", "admin",
                null, null, null, 2, 1, 7, null));

        boolean found = executor.cancel(1, 3);

        assertThat(found).isFalse();
        verify(flows).finishRun(3, "CANCELED", 7, "运行已取消", 1, 0);
        verify(flows).markFlow(1, "CANCELED", 7);
    }
}
