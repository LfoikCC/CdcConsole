package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RuntimeMetricsTest {
    @Test void firstSampleIdleResetAndResumeUseIndependentBaselines() {
        var tasks=mock(TaskRepository.class);var configs=mock(ConfigRepository.class);
        var source=mock(SourceConfig.class);when(source.dbType()).thenReturn(DatabaseType.STARROCKS);
        when(configs.source(1L,false)).thenReturn(source);
        var table=mock(SyncTaskTable.class);when(table.processedRows()).thenReturn(100L);
        var task=mock(SyncTask.class);when(task.id()).thenReturn(1L);when(task.sourceId()).thenReturn(1L);when(task.tables()).thenReturn(List.of(table));
        when(tasks.samplingTasks()).thenReturn(List.of(task));
        var events=mock(RuntimeEventService.class);
        var runtime=new TaskRuntimeService(tasks,mock(SqlServerCdcPoller.class),configs,mock(TaskConfigurationService.class),mock(TaskRuntimeCoordinator.class),events,mock(ReconciliationService.class),mock(ReconciliationRepository.class),7,60000);
        runtime.sampleMetrics();runtime.sampleMetrics();
        verify(tasks,times(2)).sampleMetrics(1L,0,0d,0d);
        when(table.processedRows()).thenReturn(120L);runtime.sampleMetrics();
        verify(tasks).sampleMetrics(eq(1L),eq(0L),doubleThat(v->v>0),doubleThat(v->v>0));
        clearInvocations(tasks);
        runtime.samplingReset(new TaskRepository.SamplingReset(1L));
        when(table.processedRows()).thenReturn(400L);runtime.sampleMetrics();
        verify(tasks).sampleMetrics(1L,0,0d,0d);
        clearInvocations(tasks);when(tasks.samplingTasks()).thenReturn(List.of());runtime.sampleMetrics();
        verify(tasks,never()).sampleMetrics(anyLong(),anyLong(),anyDouble(),anyDouble());
        when(tasks.samplingTasks()).thenReturn(List.of(task));when(table.processedRows()).thenReturn(1L);runtime.sampleMetrics();
        verify(tasks).sampleMetrics(1L,0,0d,0d);
        verify(events,times(6)).publish(eq("metrics-updated"),any());
    }
}
