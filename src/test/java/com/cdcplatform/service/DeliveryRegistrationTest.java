package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.*;
import com.cdcplatform.service.delivery.TargetWriterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:registration-test;DB_CLOSE_DELAY=-1","cdc.cache.directory=target/registration-test-cache-${random.uuid}"})
class DeliveryRegistrationTest {
    @Autowired DeliveryService delivery;
    @Autowired ConfigRepository configs;
    @Autowired TaskRepository tasks;
    @Autowired TaskSubscriptionRepository subscriptions;
    @Autowired TaskSubscriptionService subscriptionService;
    @Autowired CaptureGroupService capture;
    @Autowired SegmentCache cache;
    @Autowired JdbcClient db;
    @MockitoSpyBean DeliveryBatchRepository batches;
    @MockitoBean TaskRuntimeCoordinator coordinator;
    @MockitoBean SqlServerCdcPoller poller;
    @MockitoBean TargetWriterRegistry writers;
    @MockitoBean ConnectionProbeService probe;

    @Test void diskBatchRecoversAllTargetsAfterMetadataTransactionRollsBack() {
        String name=UUID.randomUUID().toString();
        long source=configs.addSource(new SourceConfig(null,name,DatabaseType.MYSQL,"localhost",3306,"demo","root","secret",1000,false,null));
        long target=configs.addTarget(new TargetConfig(null,name,TargetType.MYSQL,Map.of("host","localhost","database","demo","username","root","password","secret"),false,null));
        var table=new SyncTaskTable(null,null,"demo",null,"orders",null,"orders_a",Map.of("id","id"),List.of("id"),false,false,null,null,null,Map.of(),null,null,null,TableStatus.DRAFT,0,0,null,null,null);
        long task=tasks.create(new SyncTask(null,name,"",source,target,SyncMode.INCREMENTAL_ONLY,null,null,TaskStatus.DRAFT,null,null,null,List.of(table),null,null));
        var a=subscriptionService.list(task).getFirst().tables().getFirst();
        long other=subscriptions.createSubscription(task,target,"B","","ACTIVE");
        var mapping=new TableMapping(null,source,target,"demo",null,"orders",null,"orders_b",Map.of("id","id"),false,false,null,null,null,Map.of(),false);
        long mid=configs.addMapping(mapping);
        long runtime=subscriptions.createRuntimeTable(other,task,table,configs.mapping(mid),ReconciliationConfig.disabled());
        long group=capture.ensureForMapping(a.mappingId());capture.ensureForMapping(mid);
        subscriptions.createTable(other,a.sourceTaskTableId(),runtime,mid,group,"ACTIVE");
        var event=new ChangeEvent(UUID.randomUUID(),a.mappingId(),Operation.UPDATE,Map.of(),Map.of("id",1),Instant.now());
        doThrow(new IllegalStateException("injected metadata failure")).when(batches).updateCaptureMetadata(anyString(),anyList());
        assertThatThrownBy(()->delivery.stage(List.of(event),"MYSQL:bin.000001:10","MYSQL:bin.000001:20",0)).hasMessageContaining("metadata failure");
        assertThat(db.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE task_id=:t").param("t",task).query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT COUNT(*) FROM cdc_capture_batch WHERE capture_group_id=:g").param("g",group).query(Long.class).single()).isZero();
        doCallRealMethod().when(batches).updateCaptureMetadata(anyString(),anyList());
        delivery.recover();
        var descriptor=cache.readyBatches().stream().filter(d->d.mappingId()==a.mappingId()).findFirst().orElseThrow();
        assertThat(batches.exists(descriptor.batchId())).isTrue();
        assertThat(batches.exists(descriptor.batchId()+"-m"+mid)).isTrue();
        assertThat(db.sql("SELECT COUNT(*) FROM cdc_capture_batch WHERE capture_group_id=:g").param("g",group).query(Long.class).single()).isEqualTo(1);
        delivery.recover();
        assertThat(db.sql("SELECT COUNT(*) FROM cdc_delivery_batch WHERE task_id=:t").param("t",task).query(Long.class).single()).isEqualTo(2);
        verifyNoInteractions(writers);
    }
}
