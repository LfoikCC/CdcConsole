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
    @Test void cancelStopsChildMappingBeforePublishingCancelledParent() {
        var tasks=mock(TaskRepository.class);var subscriptions=mock(TaskSubscriptionRepository.class);
        var groups=mock(CaptureGroupService.class);var delivery=mock(DeliveryService.class);
        var table=new SyncTaskTable(4248L,4247L,"db",null,"orders",null,"sr_orders",Map.of("id","id"),List.of("id"),false,false,null,null,null,Map.of(),null,null,null,TableStatus.RUNNING,0,0,null,null,null);
        var binding=new TaskTargetSubscriptionTable(4250L,4246L,3346L,4248L,3816L,4247L,"ACTIVE",null,null,null,table);
        var subscription=new TaskTargetSubscription(4246L,3344L,116L,"target","","ACTIVE","ACTIVE",null,0,0,null,null,null,List.of(binding));
        when(subscriptions.subscription(4246L)).thenReturn(subscription);
        var service=new TaskSubscriptionService(tasks,subscriptions,mock(ConfigRepository.class),groups,mock(ConnectionProbeService.class),delivery,mock(SegmentCache.class),new ObjectMapper(),mock(RuntimeEventService.class),mock(RecoveryService.class));
        service.action(3344L,4246L,"cancel");
        var order=inOrder(delivery,subscriptions,groups);
        order.verify(delivery).suspendAndAwait(4247L);
        order.verify(subscriptions).updateTableState(4250L,"CANCELLED",null,false);
        order.verify(subscriptions).updateRuntimeStatus(4248L,TableStatus.STOPPED);
        order.verify(subscriptions).updateSubscriptionState(4246L,"CANCELLED",null,false);
        order.verify(groups).setMappingSubscriptionStatus(4247L,"CANCELLED",null);
    }

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
        var recovery=mock(RecoveryService.class);
        var service = new TaskSubscriptionService(tasks, subscriptions, configs, groups, connections,
                delivery, mock(SegmentCache.class), new ObjectMapper(), mock(RuntimeEventService.class), recovery);

        service.initialize(4246L);

        verify(tasks, timeout(5000)).addLog(eq(3344L), isNull(), eq("INFO"), contains("初始化完成"));
        var order = inOrder(groups, recovery, subscriptions);
        order.verify(groups).setMappingSubscriptionStatus(4247L, "INITIALIZING_SNAPSHOT", null);
        order.verify(recovery).initializeAndAwait(3344L,binding);
        verify(delivery,never()).resume(anyLong());
        order.verify(groups).setMappingSubscriptionStatus(4247L, "ACTIVE", null);
        order.verify(subscriptions).updateTableState(4250L, "ACTIVE", null, true);
        verify(subscriptions, never()).updateSubscriptionState(eq(4246L), eq("FAILED"), any(), anyBoolean());
    }
}
