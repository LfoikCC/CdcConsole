package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.repository.*;
import com.cdcplatform.service.delivery.TargetWriterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one target table at a time. Never changes a shared source offset. */
@Service
public class RecoveryService {
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","CANCELLED");
    private final JdbcClient db;
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final TaskSubscriptionRepository subscriptions;
    private final DeliveryService delivery;
    private final DeliveryBatchRepository batches;
    private final TargetWriterRegistry writers;
    private final SegmentCache cache;
    private final ConnectionProbeService probe;
    private final RuntimeEventService events;
    private final AuditLogService audit;
    private final TransactionTemplate tx;
    private final CaptureProgressService progress;
    private final Set<String> active=ConcurrentHashMap.newKeySet();

    public RecoveryService(JdbcClient db,TaskRepository tasks,ConfigRepository configs,TaskSubscriptionRepository subscriptions,
            DeliveryService delivery,DeliveryBatchRepository batches,TargetWriterRegistry writers,SegmentCache cache,
            ConnectionProbeService probe,RuntimeEventService events,AuditLogService audit,PlatformTransactionManager transactions,CaptureProgressService progress) {
        this.db=db;this.tasks=tasks;this.configs=configs;this.subscriptions=subscriptions;this.delivery=delivery;
        this.batches=batches;this.writers=writers;this.cache=cache;this.probe=probe;this.events=events;this.audit=audit;
        this.tx=new TransactionTemplate(transactions);
        this.progress=progress;
    }
    private boolean running(long id){return Set.of(Types.TaskStatus.STARTING,Types.TaskStatus.SNAPSHOTTING,Types.TaskStatus.RUNNING,Types.TaskStatus.DEGRADED).contains(tasks.task(id).status());}
    private TaskTargetSubscriptionTable binding(long task,long id){return subscriptions.subscriptions(task).stream().flatMap(s->s.tables().stream()).filter(t->t.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("目标订阅表不属于当前任务"));}
    private long version(long id){return db.sql("SELECT mapping_version FROM target_subscription_table WHERE id=:id").param("id",id).query(Long.class).single();}
    private long generation(long group){return db.sql("SELECT generation FROM cdc_capture_group WHERE id=:id").param("id",group).query(Long.class).single();}
    public boolean busy(long mapping){return batches.recoveryActive(mapping);}
    public void requireIdle(long mapping){if(busy(mapping))throw new IllegalStateException("该目标表已有未完成恢复作业，请先处理恢复作业");}
    public void lockBinding(long id){db.sql("SELECT id FROM target_subscription_table WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).single();}

    public synchronized Map<String,Object> precheck(long task,Map<String,Object> body,ConsoleIdentityService.User user){
        if(!running(task))throw new IllegalStateException("请先启动或恢复任务，再提交重跑");
        var b=binding(task,Long.parseLong(Objects.toString(body.get("subscriptionTableId"),"0")));
        if("CANCELLED".equals(subscriptions.subscription(b.subscriptionId()).status()))throw new IllegalStateException("目标订阅已取消，不能提交恢复作业");
        if(b.captureGroupId()==null)throw new IllegalStateException("该源表尚未建立采集组");
        requireIdle(b.mappingId());
        String mode=Objects.toString(body.get("mode"),"LAST_SUCCESS");
        if(!Set.of("FULL","TIME","FAILED_BATCH","LAST_SUCCESS").contains(mode))throw new IllegalArgumentException("恢复模式无效");
        var target=configs.target(configs.mapping(b.mappingId()).targetId(),true);
        if(target.targetType()==Types.TargetType.KAFKA)probe.topics(target);else probe.tables(target);
        if(target.targetType()!=Types.TargetType.KAFKA&&(b.mapping().keyColumns().isEmpty()||!b.mapping().columns().keySet().containsAll(b.mapping().keyColumns())))throw new IllegalArgumentException("源主键必须完整映射到目标");
        long start=0,boundary=maxSequence(b.captureGroupId()),gen=generation(b.captureGroupId());OffsetDateTime requested=null;
        if(mode.equals("TIME")){
            requested=OffsetDateTime.parse(Objects.toString(body.get("startAt"),""));
            start=db.sql("SELECT sequence_no FROM cdc_capture_batch WHERE capture_group_id=:g AND batch_kind<>'TARGET_SNAPSHOT' AND COALESCE(captured_to,created_at)>=:at ORDER BY sequence_no LIMIT 1")
                    .param("g",b.captureGroupId()).param("at",requested).query(Long.class).optional().orElseThrow(()->new IllegalArgumentException("指定时间没有可回放批次"));
            var earliest=db.sql("SELECT MIN(COALESCE(captured_from,created_at)) FROM cdc_capture_batch WHERE capture_group_id=:g AND batch_kind<>'TARGET_SNAPSHOT'").param("g",b.captureGroupId()).query(OffsetDateTime.class).optional().orElse(null);
            if(earliest==null||requested.isBefore(earliest))throw new IllegalArgumentException("指定时间早于历史覆盖范围，请选择保留范围或全量初始化");
        }else if(!mode.equals("FULL")){
            String filter=mode.equals("FAILED_BATCH")?"d.status='FAILED' AND d.batch_id=:batch":"d.status NOT IN ('SUCCEEDED','CANCELLED')";
            var q=db.sql("SELECT MIN(c.sequence_no) FROM cdc_delivery_batch d JOIN cdc_capture_batch c ON c.segment_path=d.segment_path WHERE d.mapping_id=:mapping AND d.recovery_id IS NULL AND "+filter).param("mapping",b.mappingId());
            if(mode.equals("FAILED_BATCH"))q.param("batch",Objects.toString(body.get("batchId"),""));
            start=q.query(Long.class).optional().orElse(0L);
            if(start==0)throw new IllegalArgumentException("没有可继续的失败或待投递批次");
        }
        String id=UUID.randomUUID().toString();long count=0,bytes=0;OffsetDateTime actual=null;
        synchronized(delivery){
            if(!mode.equals("FULL")){
                long cursor=start-1,previous=-1;
                while(true){var page=captures(b.captureGroupId(),cursor,boundary);if(page.isEmpty())break;
                    for(var row:page){checkBatch(row,b,gen,previous);pin(id,(String)row.get("SEGMENT_PATH"));if(actual==null)actual=(OffsetDateTime)(row.get("CAPTURED_FROM")==null?row.get("CREATED_AT"):row.get("CAPTURED_FROM"));count+=number(row,"EVENT_COUNT");bytes+=number(row,"PAYLOAD_BYTES");previous=cursor=number(row,"SEQUENCE_NO");}
                }
                if(previous!=boundary)throw new IllegalStateException("本地历史覆盖不完整，需要全量初始化");
            }
            db.sql("INSERT INTO recovery_plan(id,task_id,subscription_table_id,mapping_version,generation,mode,start_sequence,boundary_sequence,requested_at,actor_id,expires_at) VALUES(:id,:task,:table,:v,:gen,:mode,:start,:end,:at,:actor,:expires)")
                    .param("id",id).param("task",task).param("table",b.id()).param("v",version(b.id())).param("gen",gen).param("mode",mode).param("start",start).param("end",boundary).param("at",requested).param("actor",user.id()).param("expires",OffsetDateTime.now().plusMinutes(5)).update();
        }
        var result=new LinkedHashMap<String,Object>();result.put("planId",id);result.put("events",count);result.put("bytes",bytes);result.put("actualStart",actual);result.put("sourceTable",b.mapping().sourceTable());result.put("targetTable",b.mapping().targetTable());result.put("mode",mode);result.put("expiresInSeconds",300);result.put("kafka",target.targetType()==Types.TargetType.KAFKA);
        result.put("warning",target.targetType()==Types.TargetType.KAFKA?"重新发送消息，不删除历史消息；下游需按事件 ID 去重":"按主键覆盖已有记录，不清空目标；源端已不存在的目标历史行不会自动删除");
        return result;
    }

    public synchronized String create(long task,Map<String,Object> body,ConsoleIdentityService.User user){
        String id=Objects.toString(body.get("planId"),"");
        var rows=db.sql("SELECT * FROM recovery_plan WHERE id=:id AND task_id=:task AND actor_id=:actor AND expires_at>CURRENT_TIMESTAMP").param("id",id).param("task",task).param("actor",user.id()).query().listOfRows();
        if(rows.isEmpty())throw new IllegalStateException("预检计划已失效，请重新预检");
        var plan=rows.getFirst();var b=binding(task,number(plan,"SUBSCRIPTION_TABLE_ID"));requireIdle(b.mappingId());
        if(!running(task)||version(b.id())!=number(plan,"MAPPING_VERSION")||generation(b.captureGroupId())!=number(plan,"GENERATION"))throw new IllegalStateException("任务、映射或采集 generation 已变化，请重新预检");
        if(configs.target(configs.mapping(b.mappingId()).targetId(),false).targetType()==Types.TargetType.KAFKA&&!Boolean.TRUE.equals(body.get("confirmKafkaDuplicates")))throw new IllegalArgumentException("请确认 Kafka 重复消息风险");
        synchronized(delivery){
            if(!plan.get("MODE").equals("FULL")){long cursor=number(plan,"START_SEQUENCE")-1,previous=-1,end=number(plan,"BOUNDARY_SEQUENCE");while(true){var page=captures(b.captureGroupId(),cursor,end);if(page.isEmpty())break;for(var row:page){checkBatch(row,b,number(plan,"GENERATION"),previous);previous=cursor=number(row,"SEQUENCE_NO");}}if(previous!=end)throw new IllegalStateException("历史文件已变化，请重新预检");}
            tx.executeWithoutResult(status->{
                lockBinding(b.id());requireIdle(b.mappingId());
                if(version(b.id())!=number(plan,"MAPPING_VERSION"))throw new IllegalStateException("映射已变化，请重新预检");
                insertJob(id,task,b,(String)plan.get("MODE"),number(plan,"START_SEQUENCE"),number(plan,"BOUNDARY_SEQUENCE"),(OffsetDateTime)plan.get("REQUESTED_AT"),user);
                db.sql("DELETE FROM recovery_plan WHERE id=:id").param("id",id).update();
                gate(b.mappingId(),"RECOVERING");
            });
        }
        events.publish("recovery",Map.of("taskId",task,"id",id,"status","QUEUED"));return id;
    }
    private void insertJob(String id,long task,TaskTargetSubscriptionTable b,String mode,long start,long end,OffsetDateTime at,ConsoleIdentityService.User user){
        db.sql("INSERT INTO recovery_job(id,task_id,subscription_table_id,mapping_id,mapping_version,generation,mode,status,start_sequence,cursor_sequence,boundary_sequence,requested_at,actor_id,actor) VALUES(:id,:task,:table,:mapping,:v,:gen,:mode,'QUEUED',:start,:cursor,:end,:at,:actor,:name)")
                .param("id",id).param("task",task).param("table",b.id()).param("mapping",b.mappingId()).param("v",version(b.id())).param("gen",generation(b.captureGroupId())).param("mode",mode).param("start",start).param("cursor",Math.max(0,start-1)).param("end",end).param("at",at).param("actor",user==null?null:user.id()).param("name",user==null?"系统初始化":user.username()).update();
    }
    public Object list(long task){tasks.task(task);return db.sql("SELECT * FROM recovery_job WHERE task_id=:task ORDER BY created_at DESC LIMIT 500").param("task",task).query().listOfRows();}
    public Map<String,Object> get(long task,String id){return db.sql("SELECT * FROM recovery_job WHERE task_id=:task AND id=:id").param("task",task).param("id",id).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("恢复作业不存在"));}
    public synchronized void action(long task,String id,String action){
        var job=get(task,id);String status=(String)job.get("STATUS");
        if(TERMINAL.contains(status))throw new IllegalStateException("恢复作业已结束");
        if(!Set.of("pause","resume","retry").contains(action))throw new IllegalArgumentException("恢复操作无效");
        if(!action.equals("pause")&&!running(task))throw new IllegalStateException("请先恢复任务");
        if(action.equals("retry")&&!status.equals("FAILED")||action.equals("resume")&&!status.equals("PAUSED"))throw new IllegalStateException("当前恢复阶段不能执行此操作");
        if(action.equals("retry")&&job.get("MODE").equals("FULL")&&!Boolean.TRUE.equals(job.get("SNAPSHOT_COMPLETE"))){
            // No snapshot rows have been delivered before snapshot_complete. Retrying explicitly rescans.
            db.sql("DELETE FROM recovery_file_pin WHERE owner_id=:id AND segment_path IN (SELECT segment_path FROM recovery_snapshot WHERE job_id=:id)").param("id",id).update();
            db.sql("DELETE FROM recovery_snapshot WHERE job_id=:id").param("id",id).update();
        }
        transition(id,action.equals("pause")?"PAUSED":"QUEUED",null);
    }
    @Scheduled(fixedDelay=1000) void tick(){
        synchronized(delivery){
            db.sql("DELETE FROM recovery_file_pin WHERE owner_id IN (SELECT id FROM recovery_plan WHERE expires_at<CURRENT_TIMESTAMP) OR (owner_id NOT IN (SELECT id FROM recovery_plan) AND owner_id NOT IN (SELECT id FROM recovery_job))").update();
            db.sql("DELETE FROM recovery_plan WHERE expires_at<CURRENT_TIMESTAMP").update();
        }
        for(var job:db.sql("SELECT * FROM recovery_job WHERE status NOT IN ('SUCCEEDED','CANCELLED')").query().listOfRows()){
            long task=number(job,"TASK_ID");String id=(String)job.get("ID");
            var taskStatus=db.sql("SELECT status FROM sync_task WHERE id=:id").param("id",task).query(String.class).optional().orElse("STOPPED");
            if(taskStatus.equals("STOPPED")){synchronized(this){if(!active.contains(id))cancel(id);}continue;}
            if(!running(task)){if(!Set.of("PAUSED","FAILED").contains(job.get("STATUS")))transition(id,"PAUSED","任务已暂停；恢复任务后继续恢复作业");continue;}
            if(Set.of("PAUSED","FAILED").contains(job.get("STATUS")))continue;
            if(active.add(id))Thread.startVirtualThread(()->{try{run(task,id);}catch(Exception e){synchronized(this){var status=get(task,id).get("STATUS");if(!Set.of("PAUSED","CANCELLED").contains(status))transition(id,"FAILED",safeError(e));}}finally{active.remove(id);}});
        }
    }
    private void run(long task,String id)throws Exception{
        var job=get(task,id);var b=binding(task,number(job,"SUBSCRIPTION_TABLE_ID"));delivery.suspendAndAwait(b.mappingId());
        checkVersion(job,b);
        if(job.get("MODE").equals("FULL")){
            if(!Boolean.TRUE.equals(job.get("SNAPSHOT_COMPLETE")))snapshot(task,id,b);
            long part=0;while(true){var page=db.sql("SELECT * FROM recovery_snapshot WHERE job_id=:id AND part_no>:part ORDER BY part_no LIMIT 200").param("id",id).param("part",part).query().listOfRows();if(page.isEmpty())break;
                for(var row:page){if(!allowed(task,id))return;part=number(row,"PART_NO");var file=new LinkedHashMap<>(row);file.put("SEQUENCE_NO","snapshot-"+part);file.put("GENERATION",job.get("GENERATION"));file.put("SOURCE_START_POSITION","SUBSCRIPTION_RECOVERY");file.put("SOURCE_END_POSITION","SUBSCRIPTION_RECOVERY");write(id,task,b,file);}
            }
        }
        if(job.get("MODE").equals("FULL")){
            if(!phase(task,id,"CATCHING_UP"))return;
            if(!awaitFence(task,id,b))return;
        }
        if(!phase(task,id,"REPLAYING"))return;
        while(true){
            if(!allowed(task,id))return;job=get(task,id);checkVersion(job,b);long cursor=number(job,"CURSOR_SEQUENCE"),end=maxSequence(b.captureGroupId());var page=captures(b.captureGroupId(),cursor,end);
            for(var row:page){
                if(!allowed(task,id))return;
                synchronized(delivery){checkBatch(row,b,number(job,"GENERATION"),cursor< number(job,"START_SEQUENCE")?-1:cursor);pin(id,(String)row.get("SEGMENT_PATH"));}
                long seq=number(row,"SEQUENCE_NO");write(id,task,b,row);
                db.sql("UPDATE recovery_job SET cursor_sequence=:seq,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("seq",seq).param("id",id).update();cursor=seq;
            }
            if(cursor<end){if(page.isEmpty())throw new IllegalStateException("本地历史不连续，需要全量初始化");continue;}
            synchronized(this){synchronized(delivery){
                if(!allowed(task,id))return;
                if(maxSequence(b.captureGroupId())==cursor){
                    long boundary=cursor;
                    tx.executeWithoutResult(status->{
                        db.sql("UPDATE cdc_delivery_batch SET status='CANCELLED' WHERE mapping_id=:m AND recovery_id IS NULL AND status NOT IN ('SUCCEEDED','CANCELLED') AND segment_path IN (SELECT segment_path FROM cdc_capture_batch WHERE capture_group_id=:g AND sequence_no<=:end)").param("m",b.mappingId()).param("g",b.captureGroupId()).param("end",boundary).update();
                        db.sql("UPDATE cdc_subscription_batch SET status='SUCCEEDED',delivered_at=CURRENT_TIMESTAMP WHERE subscription_id=(SELECT id FROM cdc_target_subscription WHERE mapping_id=:m) AND capture_batch_id IN (SELECT capture_batch_id FROM cdc_capture_batch WHERE capture_group_id=:g AND sequence_no<=:end)").param("m",b.mappingId()).param("g",b.captureGroupId()).param("end",boundary).update();
                        gate(b.mappingId(),"ACTIVE");transition(id,"SUCCEEDED",null);db.sql("DELETE FROM recovery_file_pin WHERE owner_id=:id").param("id",id).update();
                        db.sql("UPDATE cdc_target_subscription s SET delivery_position_json=(SELECT source_end_position FROM cdc_capture_batch WHERE capture_group_id=:g AND sequence_no=:seq),pending_events=0,pending_bytes=0,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE mapping_id=:mapping")
                                .param("g",b.captureGroupId()).param("seq",boundary).param("mapping",b.mappingId()).update();
                    });
                    delivery.resume(b.mappingId());return;
                }
            }}
            if(!phase(task,id,"CATCHING_UP"))return;
        }
    }
    private void checkVersion(Map<String,Object> job,TaskTargetSubscriptionTable b){if(version(b.id())!=number(job,"MAPPING_VERSION")||generation(b.captureGroupId())!=number(job,"GENERATION"))throw new IllegalStateException("映射版本或采集 generation 已改变，不能继续历史恢复");}
    private synchronized boolean phase(long task,String id,String phase){if(!allowed(task,id))return false;transition(id,phase,null);return true;}
    private boolean allowed(long task,String id){if(tasks.task(task).status()==Types.TaskStatus.STOPPED){cancel(id);return false;}return running(task)&&!Set.of("PAUSED","CANCELLED","FAILED").contains(get(task,id).get("STATUS"));}
    private void cancel(String id){transition(id,"CANCELLED","任务已停止，恢复作业已取消");db.sql("DELETE FROM recovery_file_pin WHERE owner_id=:id").param("id",id).update();}
    private void transition(String id,String status,String error){
        int changed=db.sql("UPDATE recovery_job SET status=:s,last_error=:error,updated_at=CURRENT_TIMESTAMP,completed_at=CASE WHEN :s IN ('SUCCEEDED','CANCELLED') THEN CURRENT_TIMESTAMP ELSE completed_at END WHERE id=:id AND status NOT IN ('SUCCEEDED','CANCELLED')")
                .param("id",id).param("s",status).param("error",error).update();if(changed==0)return;
        var job=db.sql("SELECT * FROM recovery_job WHERE id=:id").param("id",id).query().singleRow();long task=number(job,"TASK_ID");
        if(Set.of("FAILED","SUCCEEDED","CANCELLED").contains(status)){
            var actor=job.get("ACTOR_ID")==null?null:new ConsoleIdentityService.User(number(job,"ACTOR_ID"),(String)job.get("ACTOR"),"","","",true,null);
            audit.record(actor,"RECOVERY","tasks",Long.toString(task),"目标表 #"+job.get("SUBSCRIPTION_TABLE_ID"),status.equals("FAILED")?"FAILED":"SUCCESS",null,null,"恢复作业"+status+(error==null?"":"："+error),null,null,id);
        }
        String display=switch(status){case "INITIALIZING","QUEUED"->"INITIALIZING_SNAPSHOT";case "REPLAYING","CATCHING_UP"->"CATCHING_UP";case "SUCCEEDED"->"ACTIVE";default->status;};
        subscriptions.updateTableState(number(job,"SUBSCRIPTION_TABLE_ID"),display,error,status.equals("SUCCEEDED"));
        var attached=db.sql("SELECT subscription_id FROM target_subscription_table WHERE id=:id").param("id",job.get("SUBSCRIPTION_TABLE_ID")).query(Long.class).optional();
        if(attached.isEmpty()){events.publish("recovery",Map.of("taskId",task,"id",id,"status",status));return;}
        long subscription=attached.get();
        var tableStates=db.sql("SELECT status FROM target_subscription_table WHERE subscription_id=:id").param("id",subscription).query(String.class).list();
        String overall=tableStates.stream().allMatch("ACTIVE"::equals)?"ACTIVE":tableStates.contains("FAILED")?"FAILED":tableStates.contains("CATCHING_UP")?"CATCHING_UP":display;
        subscriptions.updateSubscriptionState(subscription,overall,error,overall.equals("ACTIVE"));
        events.publish("recovery",Map.of("taskId",task,"id",id,"status",status));
    }
    private void gate(long mapping,String status){db.sql("UPDATE cdc_target_subscription SET status=:s WHERE mapping_id=:id").param("s",status).param("id",mapping).update();}
    private long maxSequence(long group){return db.sql("SELECT COALESCE(MAX(sequence_no),0) FROM cdc_capture_batch WHERE capture_group_id=:g AND batch_kind<>'TARGET_SNAPSHOT'").param("g",group).query(Long.class).single();}
    private List<Map<String,Object>> captures(long group,long cursor,long end){return db.sql("SELECT * FROM cdc_capture_batch WHERE capture_group_id=:g AND batch_kind<>'TARGET_SNAPSHOT' AND sequence_no>:cursor AND sequence_no<=:end ORDER BY sequence_no LIMIT 200").param("g",group).param("cursor",cursor).param("end",end).query().listOfRows();}
    private void checkBatch(Map<String,Object> row,TaskTargetSubscriptionTable b,long gen,long previous){
        if(number(row,"GENERATION")!=gen)throw new IllegalStateException("历史采集 generation 已取消，请执行全量初始化");
        if(row.get("SOURCE_IDENTITY")!=null){
            String current=db.sql("SELECT source_identity FROM cdc_capture_group WHERE id=:id").param("id",b.captureGroupId()).query(String.class).optional().orElse(null);
            if(current!=null&&!current.equals(row.get("SOURCE_IDENTITY")))throw new IllegalStateException("历史批次的源身份不匹配，不能继续回放");
        }
        if(previous>=0&&number(row,"PREVIOUS_SEQUENCE")!=previous)throw new IllegalStateException("本地历史不连续，请执行全量初始化");
        try{
            for(var event:cache.readBatch(Path.of((String)row.get("SEGMENT_PATH")),(String)row.get("CHECKSUM"))){
                var values=event.operation()==Types.Operation.DELETE?event.before():event.after();
                if(!values.keySet().containsAll(event.operation()==Types.Operation.DELETE?b.mapping().keyColumns():b.mapping().columns().keySet()))throw new IllegalStateException("历史字段结构与当前映射不兼容");
            }
        }catch(Exception e){throw new IllegalStateException("本地文件缺失、校验失败或映射不兼容，需要全量初始化",e);}
    }
    private void pin(String owner,String path){db.sql("MERGE INTO recovery_file_pin(owner_id,segment_path) KEY(owner_id,segment_path) VALUES(:id,:path)").param("id",owner).param("path",path).update();}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private String safeError(Exception e){return e instanceof IllegalStateException||e instanceof IllegalArgumentException?Objects.toString(e.getMessage(),"恢复失败").replaceAll("(?i)(password|token|secret)=[^ &;]+","$1=***"):"恢复失败，请检查目标连接、权限和本地批次";}

    private void write(String id,long task,TaskTargetSubscriptionTable b,Map<String,Object> file)throws Exception{
        String batchId="r-"+id+"-"+file.get("SEQUENCE_NO");var now=OffsetDateTime.now();
        if(!batches.exists(batchId)){
            var target=configs.target(configs.mapping(b.mappingId()).targetId(),false);
            batches.registerRecovery(new DeliveryBatch(batchId,task,b.runtimeTaskTableId(),b.mappingId(),number(file,"GENERATION"),target.targetType(),(String)file.get("SOURCE_START_POSITION"),(String)file.get("SOURCE_END_POSITION"),(String)file.get("SEGMENT_PATH"),(String)file.get("CHECKSUM"),(int)number(file,"EVENT_COUNT"),number(file,"PAYLOAD_BYTES"),DeliveryBatch.BatchStatus.STAGED,0,null,null,now,now,null),id,file.get("SEQUENCE_NO") instanceof Number n?n.longValue():null);
        }
        if(batches.batch(batchId).status()==DeliveryBatch.BatchStatus.SUCCEEDED)return;
        var input=cache.readBatch(Path.of((String)file.get("SEGMENT_PATH")),(String)file.get("CHECKSUM"));
        var mapped=input.stream().map(e->new ChangeEvent(e.id(),b.mappingId(),e.operation(),e.before(),e.after(),e.capturedAt(),e.sourceEventAt())).toList();
        db.sql("UPDATE cdc_delivery_batch SET status='STAGED' WHERE batch_id=:id").param("id",batchId).update();batches.markDelivering(batchId);
        try{writers.writer(b.mappingId()).write(batches.batch(batchId),mapped);tx.executeWithoutResult(status->{delivery.applyDeleteAudits(batches.batch(batchId),mapped);if(batches.complete(batchId))db.sql("UPDATE recovery_job SET processed_events=processed_events+:count WHERE id=:id").param("count",mapped.size()).param("id",id).update();});}
        catch(Exception e){batches.markFailed(batchId,"目标恢复投递失败，请检查目标连接、权限和表结构");throw e;}
    }
    /** All snapshot files must be durable before any of them is delivered. */
    private void snapshot(long task,String id,TaskTargetSubscriptionTable b)throws Exception{
        if(db.sql("SELECT COUNT(*) FROM recovery_snapshot WHERE job_id=:id").param("id",id).query(Long.class).single()>0)throw new IllegalStateException("上次快照扫描未完成；重试将重新扫描源表，已落盘但不完整的快照不会作为成功快照投递");
        if(!phase(task,id,"INITIALIZING"))return;
        synchronized(delivery){long boundary=maxSequence(b.captureGroupId());db.sql("UPDATE recovery_job SET cursor_sequence=:seq,boundary_sequence=:seq,start_sequence=:start WHERE id=:id").param("seq",boundary).param("start",boundary+1).param("id",id).update();}
        var source=configs.source(tasks.task(task).sourceId(),true);var table=b.mapping();var columns=new ArrayList<>(table.columns().keySet());
        progress.verifySource(b.captureGroupId(),source,table.sourceDatabase());
        String sql="SELECT "+String.join(",",columns.stream().map(c->quote(source.dbType(),c)).toList())+" FROM "+(table.sourceSchema()==null||table.sourceSchema().isBlank()?"":quote(source.dbType(),table.sourceSchema())+".")+quote(source.dbType(),table.sourceTable());
        try(var connection=java.sql.DriverManager.getConnection(probe.streamingUrl(source,table.sourceDatabase()),source.username(),source.password())){
            connection.setReadOnly(true);connection.setAutoCommit(false);
            if(source.dbType()==Types.DatabaseType.SQLSERVER){try(var s=connection.createStatement()){s.execute("SET TRANSACTION ISOLATION LEVEL SNAPSHOT");}}
            else if(source.dbType()==Types.DatabaseType.MYSQL)connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try(var statement=connection.prepareStatement(sql)){statement.setFetchSize(1000);statement.setQueryTimeout(600);
                try(var rows=statement.executeQuery()){
                    var buffer=new ArrayList<ChangeEvent>();long index=0,part=0;
                    while(rows.next()){
                        if(!allowed(task,id))throw new IllegalStateException("快照扫描已中断，需要重新扫描");
                        var after=new LinkedHashMap<String,Object>();for(String column:columns)after.put(column,rows.getObject(column));
                        buffer.add(new ChangeEvent(UUID.nameUUIDFromBytes((id+"|"+(++index)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),b.mappingId(),Types.Operation.READ,Map.of(),after,Instant.now()));
                        if(buffer.size()==1000){snapshotPart(id,++part,b,buffer);buffer.clear();}
                    }
                    if(!buffer.isEmpty())snapshotPart(id,++part,b,buffer);
                }
            }finally{connection.rollback();}
        }
        progress.verifySource(b.captureGroupId(),source,table.sourceDatabase());
        String fence=readFence(source,b.mapping());
        synchronized(this){if(!allowed(task,id))throw new IllegalStateException("快照扫描被暂停，需要重新扫描");db.sql("UPDATE recovery_job SET snapshot_complete=TRUE,source_fence=:fence WHERE id=:id").param("id",id).param("fence",fence).update();}
    }

    private String readFence(SourceConfig source,SyncTaskTable table)throws Exception{
        if(source.dbType()==Types.DatabaseType.STARROCKS)return "POLL:"+System.currentTimeMillis();
        try(var c=java.sql.DriverManager.getConnection(probe.url(source,table.sourceDatabase()),source.username(),source.password());var s=c.createStatement()){
            s.setQueryTimeout(10);
            if(source.dbType()==Types.DatabaseType.MYSQL){
                java.sql.ResultSet r;try{r=s.executeQuery("SHOW BINARY LOG STATUS");}catch(java.sql.SQLException e){if(!"42000".equals(e.getSQLState()))throw e;r=s.executeQuery("SHOW MASTER STATUS");}
                try(var rows=r){if(!rows.next())throw new IllegalStateException("源端未启用 binlog，不能完成快照交接");return "MYSQL:"+rows.getString(1)+":"+rows.getLong(2);}
            }
            try(var r=s.executeQuery("SELECT SYSDATETIME()")){r.next();return "SQLTIME:"+r.getTimestamp(1).toLocalDateTime();}
        }
    }
    private boolean awaitFence(long task,String id,TaskTargetSubscriptionTable b)throws Exception{
        long deadline=System.currentTimeMillis()+600000;String fence=(String)get(task,id).get("SOURCE_FENCE");
        if(fence==null)throw new IllegalStateException("快照缺少持久化源端边界，请重新初始化");
        while(allowed(task,id)){
            if(fence.startsWith("SQLTIME:")){
                var source=configs.source(tasks.task(task).sourceId(),true);
                try(var c=java.sql.DriverManager.getConnection(probe.url(source,b.mapping().sourceDatabase()),source.username(),source.password());var s=c.prepareStatement("SELECT sys.fn_cdc_map_time_to_lsn('smallest greater than or equal',?)")){
                    s.setQueryTimeout(10);s.setTimestamp(1,java.sql.Timestamp.valueOf(LocalDateTime.parse(fence.substring(8))));
                    try(var r=s.executeQuery()){if(r.next()&&r.getBytes(1)!=null){String hex=java.util.HexFormat.of().formatHex(r.getBytes(1));fence="SQLSERVER:"+hex;db.sql("UPDATE recovery_job SET source_fence=:fence WHERE id=:id").param("id",id).param("fence",fence).update();}}
                }
            }else if(progress.reached(b.captureGroupId(),fence))return true;
            if(System.currentTimeMillis()>deadline)throw new IllegalStateException("源端采集尚未追过快照边界，已保留完整快照；检查采集健康度后重试");
            Thread.sleep(fence.startsWith("SQLTIME:")?5000:1000);
        }return false;
    }
    private void snapshotPart(String id,long part,TaskTargetSubscriptionTable b,List<ChangeEvent> input){
        synchronized(delivery){var file=cache.appendBatch(b.mappingId(),generation(b.captureGroupId()),"SUBSCRIPTION_RECOVERY:"+id+":"+part,"SUBSCRIPTION_RECOVERY",input);tx.executeWithoutResult(status->{pin(id,file.path().toString());db.sql("INSERT INTO recovery_snapshot(job_id,part_no,segment_path,checksum,event_count,payload_bytes) VALUES(:id,:part,:path,:checksum,:count,:bytes)").param("id",id).param("part",part).param("path",file.path().toString()).param("checksum",file.checksum()).param("count",file.eventCount()).param("bytes",file.payloadBytes()).update();});}
    }
    private String quote(Types.DatabaseType type,String value){return type==Types.DatabaseType.SQLSERVER?"["+value.replace("]","]]")+"]":"`"+value.replace("`","``")+"`";}
    /** Explicit initialization must not infer resume intent from a historical completion timestamp. */
    public synchronized void prepareFullInitialization(long task,TaskTargetSubscriptionTable b){
        var jobs=db.sql("SELECT * FROM recovery_job WHERE subscription_table_id=:id AND status NOT IN ('SUCCEEDED','CANCELLED') ORDER BY created_at DESC")
                .param("id",b.id()).query().listOfRows();
        long currentVersion=version(b.id()),currentGeneration=generation(b.captureGroupId());
        for(var job:jobs){
            boolean reusable="FULL".equals(job.get("MODE"))&&number(job,"MAPPING_VERSION")==currentVersion&&number(job,"GENERATION")==currentGeneration;
            if(!reusable&&active.contains((String)job.get("ID")))throw new IllegalStateException("目标表恢复作业仍在退出，请稍后重试初始化");
        }
        synchronized(delivery){tx.executeWithoutResult(status->{
            lockBinding(b.id());
            boolean reuse=false;
            for(var job:jobs){
                if(!reuse&&"FULL".equals(job.get("MODE"))&&number(job,"MAPPING_VERSION")==currentVersion&&number(job,"GENERATION")==currentGeneration){reuse=true;continue;}
                String id=(String)job.get("ID");
                transition(id,"CANCELLED","已由目标专属全量初始化替代；历史恢复记录保留");
                db.sql("DELETE FROM recovery_file_pin WHERE owner_id=:id").param("id",id).update();
            }
            if(!reuse)insertJob(UUID.randomUUID().toString(),task,b,"FULL",0,maxSequence(b.captureGroupId()),null,null);
            gate(b.mappingId(),"RECOVERING");
        });}
    }
    public void initializeAndAwait(long task,TaskTargetSubscriptionTable b)throws Exception{
        String id;
        synchronized(this){var existing=db.sql("SELECT id FROM recovery_job WHERE subscription_table_id=:id AND status NOT IN ('SUCCEEDED','CANCELLED') ORDER BY created_at DESC LIMIT 1").param("id",b.id()).query(String.class).optional();
            if(existing.isPresent()){id=existing.get();String status=(String)get(task,id).get("STATUS");if("FAILED".equals(status))action(task,id,"retry");else if("PAUSED".equals(status))action(task,id,"resume");}
            else{id=UUID.randomUUID().toString();synchronized(delivery){tx.executeWithoutResult(status->{
                long end=maxSequence(b.captureGroupId());
                boolean full=b.initializationCompletedAt()==null;
                long start=full?0:db.sql("SELECT MIN(c.sequence_no) FROM cdc_subscription_batch s JOIN cdc_target_subscription t ON t.id=s.subscription_id JOIN cdc_capture_batch c ON c.capture_batch_id=s.capture_batch_id WHERE t.mapping_id=:m AND s.status NOT IN ('SUCCEEDED','CANCELLED') AND c.batch_kind<>'TARGET_SNAPSHOT'").param("m",b.mappingId()).query(Long.class).optional().orElse(end+1);
                insertJob(id,task,b,full?"FULL":"LAST_SUCCESS",start,end,null,null);gate(b.mappingId(),"RECOVERING");
            });}}
        }
        long deadline=System.currentTimeMillis()+1800000;
        while(true){var job=get(task,id);String status=(String)job.get("STATUS");if(status.equals("SUCCEEDED"))return;if(Set.of("FAILED","PAUSED","CANCELLED").contains(status))throw new IllegalStateException(Objects.toString(job.get("LAST_ERROR"),"初始化已暂停"));if(System.currentTimeMillis()>deadline){transition(id,"FAILED","初始化超过30分钟，已保留恢复游标，请检查后重试");throw new IllegalStateException("初始化超过30分钟，请在位点页查看并重试恢复作业");}Thread.sleep(500);}
    }

    public void pauseTable(long task,TaskTargetSubscriptionTable b){
        synchronized(this){var jobs=db.sql("SELECT id FROM recovery_job WHERE mapping_id=:id AND status NOT IN ('SUCCEEDED','CANCELLED','FAILED')").param("id",b.mappingId()).query(String.class).list();for(String id:jobs)transition(id,"PAUSED",null);}
        delivery.suspendAndAwait(b.mappingId());gate(b.mappingId(),"PAUSED");
    }
}
