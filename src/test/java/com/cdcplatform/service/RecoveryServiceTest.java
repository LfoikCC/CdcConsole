package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.*;
import com.cdcplatform.service.delivery.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:recovery-test;DB_CLOSE_DELAY=-1","cdc.cache.directory=target/recovery-test-cache"})
class RecoveryServiceTest {
    @Autowired RecoveryService service;
    @Autowired ConfigRepository configs;
    @Autowired TaskRepository tasks;
    @Autowired TaskSubscriptionRepository subscriptions;
    @Autowired TaskSubscriptionService subscriptionService;
    @Autowired CaptureGroupRepository groups;
    @Autowired CaptureGroupService capture;
    @Autowired DeliveryBatchRepository batches;
    @Autowired ConsoleIdentityService users;
    @Autowired SegmentCache cache;
    @Autowired JdbcClient db;
    @MockitoBean DeliveryService delivery;
    @MockitoBean TaskRuntimeCoordinator coordinator;
    @MockitoBean SqlServerCdcPoller poller;
    @MockitoBean TargetWriterRegistry writers;
    @MockitoBean ConnectionProbeService probe;
    @MockitoBean CaptureProgressService progress;

    record Fixture(long task,TaskTargetSubscriptionTable a,TaskTargetSubscriptionTable b,long group){}
    Fixture fixture(){
        String name=UUID.randomUUID().toString();
        long source=configs.addSource(new SourceConfig(null,name,DatabaseType.MYSQL,"localhost",3306,"demo","root","secret",1000,false,null));
        long target=configs.addTarget(new TargetConfig(null,name,TargetType.MYSQL,Map.of("host","localhost","database","demo","username","root","password","secret"),false,null));
        var t=new SyncTaskTable(null,null,"demo",null,"orders",null,"orders_a",Map.of("id","id"),List.of("id"),false,false,null,null,null,Map.of(),null,null,null,TableStatus.DRAFT,0,0,null,null,null);
        long task=tasks.create(new SyncTask(null,name,"",source,target,SyncMode.INCREMENTAL_ONLY,null,null,TaskStatus.DRAFT,null,null,null,List.of(t),null,null));
        var a=subscriptionService.list(task).getFirst().tables().getFirst();
        long other=subscriptions.createSubscription(task,target,"B","","ACTIVE");
        var mapping=new TableMapping(null,source,target,"demo",null,"orders",null,"orders_b",Map.of("id","id"),false,false,null,null,null,Map.of(),false);
        long mid=configs.addMapping(mapping);mapping=configs.mapping(mid);
        long runtime=subscriptions.createRuntimeTable(other,task,t,mapping,ReconciliationConfig.disabled());
        long group=capture.ensureForMapping(a.mappingId());capture.ensureForMapping(mid);
        long bid=subscriptions.createTable(other,a.sourceTaskTableId(),runtime,mid,group,"ACTIVE");
        db.sql("UPDATE sync_task SET status='RUNNING' WHERE id=:id").param("id",task).update();
        groups.updateCapturePosition(group,"MYSQL:mysql-bin.000001:500",0);
        return new Fixture(task,a,subscriptions.subscription(other).tables().getFirst(),group);
    }
    CaptureBatch file(Fixture f,int index){
        var event=new ChangeEvent(UUID.randomUUID(),f.a.mappingId(),Operation.UPDATE,Map.of(),Map.of("id",index),Instant.now());
        var file=cache.appendBatch(f.a.mappingId(),0,"MYSQL:mysql-bin.000001:"+index,"MYSQL:mysql-bin.000001:"+(index+1),List.of(event));
        var c=new CaptureBatch(file.batchId(),f.group,0,"MYSQL:mysql-bin.000001:"+index,"MYSQL:mysql-bin.000001:"+(index+1),file.path().toString(),file.checksum(),1,file.payloadBytes(),"READY",OffsetDateTime.now());
        groups.registerCaptureBatch(c);groups.fanOut(c);
        db.sql("UPDATE cdc_capture_batch SET captured_from=:start,captured_to=:end WHERE capture_batch_id=:id")
                .param("start",c.createdAt().minusSeconds(1)).param("end",c.createdAt().plusSeconds(1)).param("id",c.captureBatchId()).update();
        return c;
    }
    Map<String,Object> precheck(Fixture f,CaptureBatch first){
        return service.precheck(f.task,Map.of("subscriptionTableId",f.a.id(),"mode","TIME","startAt",first.createdAt().toString()),users.authenticate("admin","admin123"));
    }
    void await(String id,long task,String expected)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline){service.tick();if(expected.equals(service.get(task,id).get("STATUS")))return;Thread.sleep(50);}
        assertThat(service.get(task,id)).containsEntry("STATUS",expected);
    }
    @Test void replayRetriesWithSameLabelAndEventIdsWithoutRewindingSourceOrTouchingOtherTarget()throws Exception{
        var f=fixture();var first=file(f,1);var second=file(f,2);
        var ids=new CopyOnWriteArrayList<String>();var events=new CopyOnWriteArrayList<UUID>();var fail=new AtomicBoolean(true);
        var writer=mock(TargetBatchWriter.class);
        when(writers.writer(f.a.mappingId())).thenReturn(writer);
        when(writer.write(any(),anyList())).thenAnswer(invocation->{DeliveryBatch batch=invocation.getArgument(0);List<ChangeEvent> rows=invocation.getArgument(1);ids.add(batch.batchId());events.add(rows.getFirst().id());if(fail.getAndSet(false))throw new IllegalStateException("test failure");return null;});
        var actor=users.authenticate("admin","admin123");var plan=precheck(f,first);
        String id=service.create(f.task,Map.of("planId",plan.get("planId")),actor);
        await(id,f.task,"FAILED");
        assertThat(db.sql("SELECT COUNT(*) FROM recovery_file_pin WHERE owner_id=:id").param("id",id).query(Long.class).single()).isEqualTo(2);
        service.action(f.task,id,"retry");await(id,f.task,"SUCCEEDED");
        assertThat(ids).hasSize(3);assertThat(ids.get(0)).isEqualTo(ids.get(1));assertThat(events.get(0)).isEqualTo(events.get(1));
        verify(writers,never()).writer(f.b.mappingId());
        assertThat(groups.group(f.group).capturePositionJson()).isEqualTo("MYSQL:mysql-bin.000001:500");
        assertThat(tasks.table(f.a.runtimeTaskTableId()).processedRows()).isZero();
        assertThat(service.get(f.task,id)).containsEntry("PROCESSED_EVENTS",2L);
        assertThat(subscriptions.subscription(f.a.subscriptionId()).pendingEvents()).isZero();
        assertThat(subscriptions.subscription(f.b.subscriptionId()).pendingEvents()).isEqualTo(2);
        assertThat(db.sql("SELECT COUNT(*) FROM recovery_file_pin WHERE owner_id=:id").param("id",id).query(Long.class).single()).isZero();
    }
    @Test void refusesBrokenHistoryAndRevalidatesGenerationAtSubmission()throws Exception{
        var f=fixture();var first=file(f,10);var last=file(f,11);
        var plan=precheck(f,first);
        db.sql("UPDATE cdc_capture_group SET generation=1 WHERE id=:id").param("id",f.group).update();
        assertThatThrownBy(()->service.create(f.task,Map.of("planId",plan.get("planId")),users.authenticate("admin","admin123"))).hasMessageContaining("generation");
        db.sql("UPDATE cdc_capture_group SET generation=0 WHERE id=:id").param("id",f.group).update();
        Files.delete(java.nio.file.Path.of(first.segmentPath()));
        assertThatThrownBy(()->precheck(f,first)).hasMessageContaining("文件缺失");
        assertThat(service.busy(f.a.mappingId())).isFalse();
    }
    @Test void persistentPauseGuardsIncludeInternalTargetMappings(){
        var f=fixture();
        assertThat(tasks.captureActive(f.b.mappingId())).isTrue();
        db.sql("UPDATE sync_task SET status='PAUSED' WHERE id=:id").param("id",f.task).update();
        assertThat(tasks.captureActive(f.a.mappingId())).isFalse();assertThat(tasks.captureActive(f.b.mappingId())).isFalse();
        db.sql("UPDATE cdc_target_subscription SET status='PAUSED' WHERE mapping_id=:m").param("m",f.b.mappingId()).update();
        assertThat(batches.subscriptionActive(f.b.mappingId())).isFalse();
    }

    @Test void completedSnapshotSurvivesRestartAndDoesNotScanSourceAgain()throws Exception{
        var f=fixture();String id=UUID.randomUUID().toString();
        var snapshot=cache.appendBatch(f.a.mappingId(),0,"SUBSCRIPTION_RECOVERY:"+id,"SUBSCRIPTION_RECOVERY",List.of(new ChangeEvent(UUID.randomUUID(),f.a.mappingId(),Operation.READ,Map.of(),Map.of("id",42),Instant.now())));
        db.sql("INSERT INTO recovery_job(id,task_id,subscription_table_id,mapping_id,mapping_version,generation,mode,status,start_sequence,cursor_sequence,boundary_sequence,snapshot_complete,source_fence) VALUES(:id,:task,:table,:mapping,1,0,'FULL','PAUSED',1,0,0,TRUE,'MYSQL:mysql-bin.000001:500')")
                .param("id",id).param("task",f.task).param("table",f.a.id()).param("mapping",f.a.mappingId()).update();
        db.sql("INSERT INTO recovery_snapshot(job_id,part_no,segment_path,checksum,event_count,payload_bytes) VALUES(:id,1,:path,:checksum,1,:bytes)")
                .param("id",id).param("path",snapshot.path().toString()).param("checksum",snapshot.checksum()).param("bytes",snapshot.payloadBytes()).update();
        when(progress.reached(eq(f.group),anyString())).thenReturn(true);
        var writer=mock(TargetBatchWriter.class);when(writers.writer(f.a.mappingId())).thenReturn(writer);
        service.action(f.task,id,"resume");await(id,f.task,"SUCCEEDED");
        verify(writer).write(argThat(batch->batch.batchId().equals("r-"+id+"-snapshot-1")),anyList());
        verify(probe,never()).streamingUrl(any(),anyString());
        assertThat(service.get(f.task,id)).containsEntry("PROCESSED_EVENTS",1L);
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void explicitInitializationReplacesOldGenerationResumeDespitePreviousCompletion(){
        var f=fixture();file(f,20);
        db.sql("UPDATE target_subscription_table SET initialization_completed_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",f.a.id()).update();
        String old=UUID.randomUUID().toString();
        db.sql("INSERT INTO recovery_job(id,task_id,subscription_table_id,mapping_id,mapping_version,generation,mode,status,start_sequence,cursor_sequence,boundary_sequence) VALUES(:id,:task,:table,:mapping,1,0,'LAST_SUCCESS','FAILED',1,0,1)")
                .param("id",old).param("task",f.task).param("table",f.a.id()).param("mapping",f.a.mappingId()).update();
        db.sql("INSERT INTO recovery_file_pin(owner_id,segment_path) VALUES(:id,'old-file')").param("id",old).update();
        db.sql("UPDATE cdc_capture_group SET generation=1 WHERE id=:id").param("id",f.group).update();
        var b=subscriptions.subscription(f.a.subscriptionId()).tables().getFirst();
        service.prepareFullInitialization(f.task,b);
        var replacement=db.sql("SELECT * FROM recovery_job WHERE subscription_table_id=:id AND status='QUEUED'").param("id",b.id()).query().singleRow();
        assertThat(replacement).containsEntry("MODE","FULL").containsEntry("GENERATION",1L);
        assertThat(service.get(f.task,old)).containsEntry("STATUS","CANCELLED");
        assertThat(db.sql("SELECT COUNT(*) FROM recovery_file_pin WHERE owner_id=:id").param("id",old).query(Long.class).single()).isZero();
        assertThat(subscriptions.subscription(f.a.subscriptionId()).tables().getFirst().initializationCompletedAt()).isNotNull();
        verify(delivery,never()).suspendAndAwait(f.b.mappingId());
        assertThat(groups.group(f.group).capturePositionJson()).isEqualTo("MYSQL:mysql-bin.000001:500");
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void explicitInitializationReusesCompatibleCompleteSnapshot(){
        var f=fixture();String id=UUID.randomUUID().toString();
        db.sql("INSERT INTO recovery_job(id,task_id,subscription_table_id,mapping_id,mapping_version,generation,mode,status,start_sequence,cursor_sequence,boundary_sequence,snapshot_complete,source_fence) VALUES(:id,:task,:table,:mapping,1,0,'FULL','FAILED',1,0,0,TRUE,'MYSQL:mysql-bin.000001:500')")
                .param("id",id).param("task",f.task).param("table",f.a.id()).param("mapping",f.a.mappingId()).update();
        service.prepareFullInitialization(f.task,f.a);
        assertThat(service.get(f.task,id)).containsEntry("SNAPSHOT_COMPLETE",true).containsEntry("STATUS","FAILED");
        assertThat(db.sql("SELECT COUNT(*) FROM recovery_job WHERE subscription_table_id=:id").param("id",f.a.id()).query(Long.class).single()).isEqualTo(1);
    }
}
