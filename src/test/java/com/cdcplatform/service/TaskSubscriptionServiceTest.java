package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.TaskSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskSubscriptionServiceTest {
    @Test void emptySnapshotResumesDeliveryBeforeBecomingActive() throws Exception {
        verifyInitialization(false);
    }

    @Test void nonEmptySnapshotResumesDeliveryBeforeAwaitingSnapshotBatch() throws Exception {
        verifyInitialization(true);
    }

    private void verifyInitialization(boolean withRows) throws Exception {
        var tasks = mock(TaskRepository.class);
        var subscriptions = mock(TaskSubscriptionRepository.class);
        var configs = mock(ConfigRepository.class);
        var groups = mock(CaptureGroupService.class);
        var connections = mock(ConnectionProbeService.class);
        var delivery = mock(DeliveryService.class);
        var table = new SyncTaskTable(4248L, 4247L, "db", null, "orders", null, "sr_orders",
                Map.of("id", "id"), List.of("id"), false, false, null, null, null, Map.of(),
                null, null, null, TableStatus.STARTING, 0, 0, null, null, null);
        var binding = new TaskTargetSubscriptionTable(4250L, 4246L, 3346L, 4248L, 3816L, 4247L,
                "INITIALIZING_SNAPSHOT", null, null, null, table);
        var subscription = new TaskTargetSubscription(4246L, 3344L, 116L, "extra target", "",
                "INITIALIZING_SNAPSHOT", "INITIALIZING_SNAPSHOT", null, 0, 0, null, null, null,
                List.of(binding));
        var task = new SyncTask(3344L, "test", "", 1L, 116L, SyncMode.FULL_INCREMENTAL,
                null, null, TaskStatus.RUNNING, null, null, null, List.of(table), null, null);
        var source = new SourceConfig(1L, "source", DatabaseType.MYSQL, "localhost", 3306,
                "db", "sa", "", 500, true, null);
        String url = "jdbc:h2:mem:subscription_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders(id INT PRIMARY KEY)");
            if (withRows) statement.execute("INSERT INTO orders VALUES(1)");
        }
        when(subscriptions.subscription(4246L)).thenReturn(subscription);
        when(tasks.task(3344L)).thenReturn(task);
        when(configs.source(1L, true)).thenReturn(source);
        when(connections.streamingUrl(source, "db")).thenReturn(url);
        when(groups.captureBatches(3816L)).thenReturn(List.of());
        when(delivery.stageAndAwait(anyList(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(new DeliveryService.DeliveryOutcome("snapshot", true, null));
        var service = new TaskSubscriptionService(tasks, subscriptions, configs, groups, connections,
                delivery, mock(SegmentCache.class), new ObjectMapper(), mock(RuntimeEventService.class));

        service.initialize(4246L);

        verify(tasks, timeout(5000)).addLog(eq(3344L), isNull(), eq("INFO"), contains("初始化完成"));
        var order = inOrder(groups, delivery, subscriptions);
        order.verify(groups).setMappingSubscriptionStatus(4247L, "INITIALIZING_SNAPSHOT", null);
        order.verify(delivery).resume(4247L);
        if (withRows) {
            order.verify(delivery).stageAndAwait(argThat(events -> events.size() == 1),
                    eq("SUBSCRIPTION_SNAPSHOT"), eq("SUBSCRIPTION_SNAPSHOT"), eq(0L), any());
        } else {
            verify(delivery, never()).stageAndAwait(anyList(), anyString(), anyString(), anyLong(), any());
        }
        order.verify(groups).setMappingSubscriptionStatus(4247L, "ACTIVE", null);
        order.verify(subscriptions).updateTableState(4250L, "ACTIVE", null, true);
        verify(subscriptions, never()).updateSubscriptionState(eq(4246L), eq("FAILED"), any(), anyBoolean());
    }
}
