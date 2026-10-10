package com.cdcplatform.service;

import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.SyncTask;
import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.ReconciliationRepository;
import com.cdcplatform.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskRuntimeServiceTest {
    @Test void samplesReadAndWriteRatesIndependently() throws Exception {
        var tasks = mock(TaskRepository.class);
        var configs = mock(ConfigRepository.class);
        var coordinator = mock(TaskRuntimeCoordinator.class);
        var runtime = new TaskRuntimeService(tasks, mock(SqlServerCdcPoller.class), configs,
                mock(TaskConfigurationService.class), coordinator, mock(RuntimeEventService.class),
                mock(ReconciliationService.class), mock(ReconciliationRepository.class), 7, 30);
        var table = new SyncTaskTable(3L, 4L, "db", null, "orders", null, "orders_copy",
                Map.of("id", "id"), List.of("id"), false, false, null, null, null, Map.of(),
                null, null, null, null, 0, 0, null, null, null);
        var updated = new SyncTaskTable(3L, 4L, "db", null, "orders", null, "orders_copy",
                Map.of("id", "id"), List.of("id"), false, false, null, null, null, Map.of(),
                null, null, null, null, 5, 0, null, null, null);
        when(tasks.tasks()).thenReturn(List.of(task(table)), List.of(task(updated)));
        when(configs.source(1L, false)).thenReturn(new SourceConfig(1L, "src", DatabaseType.MYSQL,
                "localhost", 3306, "", "user", "", 500, true, null));
        when(coordinator.readCount(4L)).thenReturn(0L, 10L);

        runtime.sampleMetrics();
        Thread.sleep(30);
        runtime.sampleMetrics();

        var reads = ArgumentCaptor.forClass(Double.class);
        var writes = ArgumentCaptor.forClass(Double.class);
        verify(tasks, times(2)).sampleMetrics(eq(7L), eq(0L), reads.capture(), writes.capture());
        assertThat(reads.getAllValues().get(1)).isGreaterThan(writes.getAllValues().get(1));
        assertThat(writes.getAllValues().get(1)).isPositive();
    }

    private SyncTask task(SyncTaskTable table) {
        return new SyncTask(7L, "rate test", "", 1L, 2L, SyncMode.FULL_INCREMENTAL,
                null, null, TaskStatus.RUNNING, null, null, null, List.of(table), null, null);
    }
}
