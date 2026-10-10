package com.cdcplatform.service;

import com.cdcplatform.model.SyncTask;
import com.cdcplatform.model.Types.TaskStatus;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.ConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read-only aggregation of local telemetry; never queries a source or target database. */
@Service
public class MonitorService {
    private final JdbcClient jdbc;
    private final TaskRepository tasks;
    private final ConfigRepository configs;
    private final long sampleMs;
    public MonitorService(JdbcClient jdbc, TaskRepository tasks, ConfigRepository configs,
                          @Value("${cdc.metrics.sample-ms:60000}") long sampleMs) {
        this.jdbc=jdbc;this.tasks=tasks;this.configs=configs;this.sampleMs=Math.max(1,sampleMs);
    }
    static boolean collecting(SyncTask task) {
        return Set.of(TaskStatus.STARTING,TaskStatus.SNAPSHOTTING,TaskStatus.RUNNING,TaskStatus.DEGRADED).contains(task.status());
    }
    static class Stats {
        OffsetDateTime at, previousAt;
        long previousRows, hourRows, lag;
        int hourSamples;
        double rate;
        final Map<Long,double[]> buckets=new TreeMap<>();
        void accept(OffsetDateTime time,long rows,long latency,double writeRate,OffsetDateTime hourStart,OffsetDateTime rangeStart,long bucketMs) {
            if(!time.isBefore(hourStart)) {
                hourSamples++;
                if(previousAt!=null) hourRows+=rows>=previousRows?rows-previousRows:Math.max(0,rows);
            }
            previousAt=time;previousRows=rows;at=time;lag=latency;rate=writeRate;
            if(!time.isBefore(rangeStart)) {
                long bucket=time.toInstant().toEpochMilli()/bucketMs;
                var value=buckets.computeIfAbsent(bucket,k->new double[2]);value[0]+=writeRate;value[1]++;
            }
        }
        List<Map<String,Object>> trend(long bucketMs) {
            return buckets.entrySet().stream().map(e->Map.<String,Object>of("at",Instant.ofEpochMilli(e.getKey()*bucketMs),"value",e.getValue()[0]/e.getValue()[1])).toList();
        }
    }
    public Map<String,Object> snapshot(String range,boolean onlyRunning,int page,int size) {
        int minutes=switch(range){case "5m"->5;case "1h"->60;case "6h"->360;case "24h"->1440;default->throw new IllegalArgumentException("监控时间范围无效");};
        var now=OffsetDateTime.now();var hourStart=now.minusHours(1);var rangeStart=now.minusMinutes(minutes);
        var since=(rangeStart.isBefore(hourStart)?rangeStart:hourStart).minusNanos(sampleMs*1_000_000);
        long bucketMs=Math.max(sampleMs,minutes*60_000L/120);
        var all=tasks.tasks();var filtered=all.stream().filter(t->!onlyRunning||collecting(t)).toList();
        int safeSize=Math.max(1,Math.min(20,size));int pages=Math.max(1,(filtered.size()+safeSize-1)/safeSize);int safePage=Math.max(0,Math.min(page,pages-1));
        var shown=filtered.stream().skip((long)safePage*safeSize).limit(safeSize).toList();
        var shownIds=shown.stream().map(SyncTask::id).collect(java.util.stream.Collectors.toSet());
        var stats=new HashMap<Long,Stats>();
        // Consume the result incrementally, retaining at most 121 trend buckets per visible task.
        jdbc.sql("SELECT task_id,sampled_at,processed_rows,lag_ms,write_rate FROM metric_sample WHERE sampled_at>=:since ORDER BY task_id,sampled_at,id")
            .param("since",since).query((RowCallbackHandler)rs->{
                long id=rs.getLong("task_id");var s=stats.computeIfAbsent(id,k->new Stats());
                s.accept(rs.getObject("sampled_at",OffsetDateTime.class),rs.getLong("processed_rows"),rs.getLong("lag_ms"),rs.getDouble("write_rate"),hourStart,
                    shownIds.contains(id)?rangeStart:now.plusYears(1),bucketMs);
            });
        var latest=new HashMap<Long,Map<String,Object>>();
        jdbc.sql("SELECT task_id,sampled_at,lag_ms,write_rate FROM (SELECT task_id,sampled_at,lag_ms,write_rate,ROW_NUMBER() OVER(PARTITION BY task_id ORDER BY sampled_at DESC,id DESC) rn FROM metric_sample) WHERE rn=1")
            .query((RowCallbackHandler)rs->latest.put(rs.getLong("task_id"),Map.of("at",rs.getObject("sampled_at",OffsetDateTime.class),"lag",rs.getLong("lag_ms"),"rate",rs.getDouble("write_rate"))));
        double throughput=0;long maxLag=0,totalHour=0;String maxTask="";boolean complete=true,maxExceeded=false;int freshCount=0;
        var destinations=new HashMap<Long,List<String>>();
        jdbc.sql("SELECT s.task_id,t.name FROM task_target_subscription s JOIN cdc_target t ON t.id=s.target_id WHERE s.status<>'CANCELLED' ORDER BY s.id")
            .query((RowCallbackHandler)rs->destinations.computeIfAbsent(rs.getLong("task_id"),k->new ArrayList<>()).add(rs.getString("name")));
        var sourceNames=new HashMap<Long,String>();var targetNames=new HashMap<Long,String>();var cards=new ArrayList<Map<String,Object>>();
        for(var task:all) {
            var s=stats.getOrDefault(task.id(),new Stats());var last=latest.get(task.id());
            OffsetDateTime at=last==null?null:(OffsetDateTime)last.get("at");
            boolean stale=at==null||at.isBefore(now.minusNanos(sampleMs*2*1_000_000));
            long lag=last==null?0:((Number)last.get("lag")).longValue();double rate=last==null?0:((Number)last.get("rate")).doubleValue();
            if(collecting(task)) {
                if(!stale) {freshCount++;throughput+=rate;if(lag>=maxLag){maxLag=lag;maxTask=task.name();maxExceeded=lag>task.alertLagSeconds()*1000L;}}
            }
            // Include rows written before a task was paused/stopped in the global one-hour total.
            if(collecting(task)||s.hourSamples>0) {
                if(s.hourSamples<2)complete=false;else totalHour+=s.hourRows;
            }
            if(!shownIds.contains(task.id()))continue;
            var card=new LinkedHashMap<String,Object>();card.put("id",task.id());card.put("name",task.name());card.put("status",task.status());
            card.put("source",sourceNames.computeIfAbsent(task.sourceId(),id->configs.source(id,false).name()));
            card.put("target",destinations.containsKey(task.id())?String.join("、",destinations.get(task.id())):targetNames.computeIfAbsent(task.targetId(),id->configs.target(id,false).name()));
            card.put("sampledAt",at);card.put("stale",stale);card.put("collecting",collecting(task));card.put("writeRate",!collecting(task)||stale?null:rate);card.put("lagMs",!collecting(task)||at==null?null:lag);
            card.put("hourRows",s.hourSamples<2?null:s.hourRows);card.put("trend",s.trend(bucketMs));card.put("alertLagSeconds",task.alertLagSeconds());
            card.put("lastEventAt",task.tables().stream().map(t->t.lastEventAt()).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            card.put("lastError",task.tables().stream().map(t->t.lastError()).filter(e->e!=null&&!e.isBlank()).findFirst().orElse(null));cards.add(card);
        }
        var alerts=tasks.alerts(com.cdcplatform.model.Types.AlertStatus.OPEN);
        var summary=new LinkedHashMap<String,Object>();summary.put("total",all.size());summary.put("collecting",all.stream().filter(MonitorService::collecting).count());
        summary.put("writeRate",throughput);summary.put("maxLagMs",freshCount==0?null:maxLag);summary.put("maxLagTask",maxTask);summary.put("maxLagExceeded",maxExceeded);summary.put("hourRows",complete?totalHour:null);
        summary.put("openAlerts",alerts.size());summary.put("latestAlert",alerts.stream().max(Comparator.comparing(a->a.createdAt())).orElse(null));
        var logs=jdbc.sql("SELECT l.id,l.task_id,l.level,l.message,l.created_at,t.name task_name FROM runtime_log l JOIN sync_task t ON t.id=l.task_id ORDER BY l.created_at DESC,l.id DESC LIMIT 50")
            .query((rs,n)->Map.<String,Object>of("id",rs.getLong("id"),"taskId",rs.getLong("task_id"),"taskName",rs.getString("task_name"),"level",rs.getString("level"),"message",rs.getString("message"),"createdAt",rs.getObject("created_at",OffsetDateTime.class))).list();
        return Map.of("summary",summary,"items",cards,"total",filtered.size(),"page",safePage,"size",safeSize,"events",logs,"updatedAt",now,"sampleMs",sampleMs);
    }
}
