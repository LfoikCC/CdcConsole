package com.cdcplatform.service;

import com.cdcplatform.repository.*;
import com.cdcplatform.model.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.*;

@Service
public class CheckpointService {
    private final JdbcClient db;
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final ConnectionProbeService probe;
    private final SegmentCache cache;
    private final Map<String,Map<String,Object>> diagnostics=new ConcurrentHashMap<>();

    public CheckpointService(JdbcClient db,TaskRepository tasks,ConfigRepository configs,ConnectionProbeService probe,SegmentCache cache){
        this.db=db;this.tasks=tasks;this.configs=configs;this.probe=probe;this.cache=cache;
    }
    public Map<String,Object> current(long taskId){
        var task=tasks.task(taskId);
        var rows=db.sql("SELECT st.id AS subscription_table_id,st.source_task_table_id,st.mapping_id,st.status,st.capture_group_id,ts.name AS subscription_name,ts.target_id,ct.source_database,ct.source_schema,ct.source_table,cg.capture_position_json,cg.generation,cg.heartbeat_at,cg.source_identity,cp.delivery_position_json,cp.updated_at AS delivery_at FROM target_subscription_table st JOIN task_target_subscription ts ON ts.id=st.subscription_id JOIN sync_task_table ct ON ct.id=st.runtime_task_table_id LEFT JOIN cdc_capture_group cg ON cg.id=st.capture_group_id LEFT JOIN sync_checkpoint cp ON cp.task_table_id=st.runtime_task_table_id WHERE ts.task_id=:task ORDER BY st.source_task_table_id,ts.id")
                .param("task",taskId).query().listOfRows();
        var result=new ArrayList<Map<String,Object>>();
        var ranges=new HashMap<Long,Map<String,Object>>();
        var lastCaptures=new HashMap<Long,Map<String,Object>>();
        for(var item:rows){
            var r=new LinkedHashMap<String,Object>(item);
            r.put("CAPTURE_POSITION_JSON",text(item.get("CAPTURE_POSITION_JSON")));
            r.put("DELIVERY_POSITION_JSON",text(item.get("DELIVERY_POSITION_JSON")));
            long mapping=((Number)item.get("MAPPING_ID")).longValue();
            var backlog=db.sql("SELECT COUNT(*) AS batches,COALESCE(SUM(event_count),0) AS events,COALESCE(SUM(payload_bytes),0) AS bytes,MIN(created_at) AS oldest FROM cdc_delivery_batch WHERE mapping_id=:id AND status NOT IN ('SUCCEEDED','CANCELLED')")
                    .param("id",mapping).query().singleRow();
            // Paused/initializing targets may not yet have delivery rows; count shared pending files too.
            var shared=db.sql("SELECT COUNT(*) AS batches,COALESCE(SUM(c.event_count),0) AS events,COALESCE(SUM(c.payload_bytes),0) AS bytes,MIN(c.created_at) AS oldest FROM cdc_subscription_batch s JOIN cdc_target_subscription t ON t.id=s.subscription_id JOIN cdc_capture_batch c ON c.capture_batch_id=s.capture_batch_id WHERE t.mapping_id=:id AND s.status NOT IN ('SUCCEEDED','CANCELLED') AND c.batch_kind<>'TARGET_SNAPSHOT'")
                    .param("id",mapping).query().singleRow();
            if(((Number)shared.get("EVENTS")).longValue()>((Number)backlog.get("EVENTS")).longValue())backlog=shared;
            r.put("backlog",backlog);
            r.put("heartbeatAt",item.get("HEARTBEAT_AT"));
            if(item.get("CAPTURE_GROUP_ID") instanceof Number n){
                long group=n.longValue();
                var last=lastCaptures.computeIfAbsent(group,g->db.sql("SELECT source_event_at,captured_to,sequence_no FROM cdc_capture_batch WHERE capture_group_id=:id AND batch_kind<>'TARGET_SNAPSHOT' ORDER BY sequence_no DESC LIMIT 1").param("id",g).query().listOfRows().stream().findFirst().orElse(Map.of()));
                r.put("lastCapture",last);
                var range=ranges.computeIfAbsent(group,g->retainedRange(g));
                r.put("availableFrom",range.get("from"));r.put("availableTo",range.get("to"));
                if(last.get("SOURCE_EVENT_AT") instanceof OffsetDateTime sourceAt&&last.get("CAPTURED_TO") instanceof OffsetDateTime capturedAt)
                    r.put("captureLagMs",Math.max(0,Duration.between(sourceAt,capturedAt).toMillis()));
                r.put("diagnosis",compare(r,diagnostics.getOrDefault(task.sourceId()+"|"+item.get("SOURCE_DATABASE"),Map.of("status","UNKNOWN","reason","尚未检查源端"))));
            }
            result.add(r);
        }
        return Map.of("taskId",taskId,"sourceType",configs.source(task.sourceId(),false).dbType().name(),"sampledAt",OffsetDateTime.now(),"tables",result);
    }
    private Map<String,Object> retainedRange(long group){
        // No source database access. A missing middle file is diagnosed by recovery precheck.
        var files=db.sql("SELECT segment_path,COALESCE(captured_from,created_at) AS start_at,COALESCE(captured_to,created_at) AS end_at FROM cdc_capture_batch WHERE capture_group_id=:g AND batch_kind<>'TARGET_SNAPSHOT' ORDER BY sequence_no DESC")
                .param("g",group).query().listOfRows();
        var result=new HashMap<String,Object>();
        for(var file:files){
            if(!Files.isRegularFile(Path.of((String)file.get("SEGMENT_PATH"))))break;
            result.put("from",file.get("START_AT"));result.putIfAbsent("to",file.get("END_AT"));
        }
        return result;
    }
    public Object history(long taskId,String range){
        tasks.task(taskId);int hours=switch(range){case "1h"->1;case "24h"->24;default->6;};
        return db.sql("SELECT * FROM checkpoint_history WHERE task_id=:id AND sampled_at>=:since ORDER BY sampled_at,id").param("id",taskId).param("since",OffsetDateTime.now().minusHours(hours)).query().listOfRows();
    }
    public List<Map<String,Object>> diagnose(long taskId,boolean force){
        var task=tasks.task(taskId);var source=configs.source(task.sourceId(),true);
        synchronized(diagnostics){
            for(var database:task.tables().stream().map(SyncTaskTable::sourceDatabase).distinct().toList()){
                String key=task.sourceId()+"|"+database;var cached=diagnostics.get(key);
                if(force||cached==null||OffsetDateTime.parse((String)cached.get("checkedAt")).isBefore(OffsetDateTime.now().minusSeconds(60)))
                    diagnostics.put(key,inspect(source,database));
            }
        }
        var current=current(taskId);var result=new ArrayList<Map<String,Object>>();
        var seen=new HashSet<Long>();
        for(var item:(List<Map<String,Object>>)current.get("tables")){
            if(!(item.get("CAPTURE_GROUP_ID") instanceof Number n)||!seen.add(n.longValue()))continue;
            var diagnosis=new LinkedHashMap<String,Object>((Map<String,Object>)item.get("diagnosis"));
            diagnosis.put("database",item.get("SOURCE_DATABASE"));diagnosis.put("table",item.get("SOURCE_TABLE"));diagnosis.put("captureGroupId",n.longValue());
            // Only outstanding source files affect current delivery. Expired successful history is normal.
            var required=db.sql("SELECT DISTINCT d.segment_path,d.checksum FROM cdc_delivery_batch d WHERE d.mapping_id IN (SELECT mapping_id FROM cdc_target_subscription WHERE capture_group_id=:g) AND d.status NOT IN ('SUCCEEDED','CANCELLED') ORDER BY d.segment_path LIMIT 50")
                    .param("g",n.longValue()).query().listOfRows();
            for(var file:required)try{cache.readBatch(Path.of((String)file.get("SEGMENT_PATH")),(String)file.get("CHECKSUM"));}
                catch(Exception e){diagnosis.put("status","LOCAL_FILE_ERROR");diagnosis.put("reason","待投递本地批次缺失或校验失败");diagnosis.put("suggestion","保留错误证据，检查磁盘和备份；不能自动跳过，可选择目标专属全量初始化。");break;}
            result.add(diagnosis);
        }
        return result;
    }
    private Map<String,Object> compare(Map<String,Object> table,Map<String,Object> source){
        var r=new LinkedHashMap<String,Object>(source);
        if(!"HEALTHY".equals(r.get("status")))return r;
        String position=text(table.get("CAPTURE_POSITION_JSON")),identity=text(table.get("SOURCE_IDENTITY")),actual=(String)source.get("identity");
        if(identity!=null&&actual!=null&&!identity.equals(actual)){r.put("status","SOURCE_CHANGED");r.put("reason","当前源身份与原采集源身份不同");r.put("suggestion","确认是否切换或恢复了源实例；不要沿用不属于当前源的位点。");return r;}
        if(position!=null&&position.startsWith("MYSQL:")&&source.get("files") instanceof List<?> files){
            String file=position.split(":")[1];
            if(!files.contains(file)){r.put("status","LOG_PURGED");r.put("reason","当前采集文件 "+file+" 不在源端 SHOW BINARY LOGS 返回的保留列表中");r.put("suggestion","确认源实例及 binlog 保留策略；使用仍保留的本地批次恢复目标，或执行全量初始化。");}
        }else if(position!=null&&position.startsWith("SQLSERVER:")&&source.get("ranges") instanceof Map<?,?> ranges){
            String key=Objects.toString(table.get("SOURCE_SCHEMA"),"dbo")+"."+table.get("SOURCE_TABLE");
            String min=Objects.toString(ranges.get(key),"");
            String lsn=position.substring(10).replace(":","");if(lsn.length()>=20)lsn=lsn.substring(0,20);
            if(min.length()==20&&lsn.matches("[0-9a-fA-F]{20}")&&lsn.compareToIgnoreCase(min)<0){r.put("status","LOG_PURGED");r.put("reason","采集 LSN 早于该表 CDC 最小保留 LSN："+min);}
            else if(min.isEmpty()){r.put("status","UNKNOWN");r.put("reason","无法取得该源表的 CDC 日志范围，不能确认位点有效");}
        }else if(position==null){r.put("status","UNKNOWN");r.put("reason","尚无已保存采集位点，无法验证");}
        return r;
    }
    private Map<String,Object> inspect(SourceConfig source,String database){
        var r=new LinkedHashMap<String,Object>();r.put("checkedAt",OffsetDateTime.now().toString());
        try(var c=DriverManager.getConnection(probe.url(source,database),source.username(),source.password());var s=c.createStatement()){
            c.setReadOnly(true);s.setQueryTimeout(8);
            switch(source.dbType()){
                case MYSQL->{
                    var files=new ArrayList<String>();try(var rows=s.executeQuery("SHOW BINARY LOGS")){while(rows.next())files.add(rows.getString(1));}
                    r.put("files",files);r.put("status",files.isEmpty()?"UNKNOWN":"HEALTHY");
                    if(!files.isEmpty()){r.put("earliest",files.getFirst());r.put("latest",files.getLast());}
                    try(var rows=s.executeQuery("SELECT @@server_uuid")){if(rows.next())r.put("identity",rows.getString(1));}
                    ResultSet latest;try{latest=s.executeQuery("SHOW BINARY LOG STATUS");}catch(SQLException e){latest=s.executeQuery("SHOW MASTER STATUS");}
                    try(var rows=latest){if(rows.next())r.put("latest","MYSQL:"+rows.getString(1)+":"+rows.getLong(2));}
                }
                case SQLSERVER->{
                    try(var rows=s.executeQuery("SELECT CONVERT(varchar(20),sys.fn_cdc_get_max_lsn(),2),CONVERT(varchar(128),SERVERPROPERTY('ServerName'))+':'+DB_NAME()")){if(rows.next()){r.put("latest",rows.getString(1));r.put("identity",rows.getString(2));}}
                    var ranges=new LinkedHashMap<String,String>();
                    try(var rows=s.executeQuery("SELECT s.name,t.name,CONVERT(varchar(20),sys.fn_cdc_get_min_lsn(ct.capture_instance),2) FROM cdc.change_tables ct JOIN sys.tables t ON t.object_id=ct.source_object_id JOIN sys.schemas s ON s.schema_id=t.schema_id")){while(rows.next())ranges.put(rows.getString(1)+"."+rows.getString(2),rows.getString(3));}
                    r.put("ranges",ranges);r.put("earliest",ranges.toString());r.put("status",ranges.isEmpty()?"UNKNOWN":"HEALTHY");
                }
                case STARROCKS->{r.put("status","HEALTHY");r.put("reason","快照差异轮询没有 binlog / LSN；轮次与轮询完成时间单独记录。");}
            }
        }catch(SQLException e){
            String state=Objects.toString(e.getSQLState(),"");
            String status=state.startsWith("28")?"AUTH_FAILED":state.startsWith("08")?"NETWORK_ERROR":Set.of(1044,1045,1142,1227,229,230,297).contains(e.getErrorCode())?"PERMISSION_DENIED":"UNKNOWN";
            r.put("status",status);r.put("reason",switch(status){case "AUTH_FAILED"->"源端认证失败";case "NETWORK_ERROR"->"源端网络连接失败";case "PERMISSION_DENIED"->"源端元数据或 CDC 查询权限不足";default->"源端只读查询失败，无法验证位点有效性";});
        }
        return r;
    }
    private String text(Object value){
        try{return value==null?null:value instanceof Clob c?c.getSubString(1,(int)c.length()):String.valueOf(value);}catch(SQLException e){return null;}
    }
    @Scheduled(fixedDelay=60000)
    void sample(){
        for(var task:tasks.samplingTasks()){
            try{
                diagnose(task.id(),false);
                for(var item:(List<Map<String,Object>>)current(task.id()).get("tables")){
                    var backlog=(Map<String,Object>)item.get("backlog");
                    db.sql("INSERT INTO checkpoint_history(task_id,subscription_table_id,capture_position,delivery_position,capture_lag_ms,pending_events,pending_bytes,delivery_wait_ms) VALUES(:task,:table,:capture,:delivery,:lag,:events,:bytes,:wait)")
                        .param("task",task.id()).param("table",item.get("SUBSCRIPTION_TABLE_ID")).param("capture",item.get("CAPTURE_POSITION_JSON")).param("delivery",item.get("DELIVERY_POSITION_JSON")).param("lag",item.get("captureLagMs"))
                        .param("events",backlog.get("EVENTS")).param("bytes",backlog.get("BYTES"))
                        .param("wait",backlog.get("OLDEST")==null?null:Math.max(0,Duration.between(((OffsetDateTime)backlog.get("OLDEST")).toInstant(),Instant.now()).toMillis())).update();
                }
            }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).debug("Checkpoint sampling unavailable for task {}",task.id(),e);}
        }
        db.sql("DELETE FROM checkpoint_history WHERE sampled_at<:cutoff").param("cutoff",OffsetDateTime.now().minusDays(7)).update();
    }
}
