package com.cdcplatform.service;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class MonitorServiceTest {
    @Test void localSnapshotExcludesStaleRatesAndPreservesLogsAndPagination() {
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:monitor_"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
        var jdbc=org.springframework.jdbc.core.simple.JdbcClient.create(ds);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var configs=new com.cdcplatform.repository.ConfigRepository(jdbc,new org.springframework.jdbc.core.JdbcTemplate(ds),json);
        var tasks=new com.cdcplatform.repository.TaskRepository(jdbc,json,configs);
        jdbc.sql("INSERT INTO cdc_source(id,name,db_type,host,port,database_name,username,password) VALUES(1,'source','MYSQL','localhost',3306,'db','u','')").update();
        jdbc.sql("INSERT INTO cdc_target(id,name,target_type,config_json) VALUES(1,'target','MYSQL','{}')").update();
        jdbc.sql("INSERT INTO sync_task(id,name,source_id,target_id,sync_mode,status) VALUES(1,'fresh',1,1,'INCREMENTAL_ONLY','RUNNING'),(2,'stale',1,1,'INCREMENTAL_ONLY','RUNNING'),(3,'paused',1,1,'INCREMENTAL_ONLY','PAUSED')").update();
        jdbc.sql("INSERT INTO metric_sample(id,task_id,sampled_at,processed_rows,lag_ms,write_rate) VALUES(1,1,DATEADD('MINUTE',-3,CURRENT_TIMESTAMP),10,100,7),(2,1,CURRENT_TIMESTAMP,30,120,9),(3,2,DATEADD('MINUTE',-4,CURRENT_TIMESTAMP),100,9000,900)").update();
        jdbc.sql("INSERT INTO runtime_log(id,task_id,level,message) VALUES(1,1,'WARN','actual event')").update();
        var service=new MonitorService(jdbc,tasks,configs,60000);
        var result=service.snapshot("5m",true,0,1);
        var summary=(java.util.Map<?,?>)result.get("summary");
        assertThat(summary.get("collecting")).isEqualTo(2L);
        assertThat(summary.get("writeRate")).isEqualTo(9d);
        assertThat(summary.get("maxLagMs")).isEqualTo(120L);
        assertThat(summary.get("hourRows")).isNull();
        assertThat(result.get("total")).isEqualTo(2);
        assertThat((java.util.List<?>)result.get("items")).hasSize(1);
        assertThat((java.util.List<?>)result.get("events")).hasSize(1);
        jdbc.sql("INSERT INTO metric_sample(id,task_id,sampled_at,processed_rows,lag_ms,write_rate) VALUES(4,2,DATEADD('MINUTE',-3,CURRENT_TIMESTAMP),110,9000,900),(5,3,DATEADD('MINUTE',-3,CURRENT_TIMESTAMP),10,0,0),(6,3,CURRENT_TIMESTAMP,22,0,0)").update();
        var completeSummary=(java.util.Map<?,?>)service.snapshot("1h",true,0,20).get("summary");
        assertThat(completeSummary.get("hourRows")).isEqualTo(42L);
        assertThat(tasks.samplingTasks()).extracting(t->t.id()).containsExactly(1L,2L);
        var cards=(java.util.List<java.util.Map<String,Object>>)service.snapshot("1h",false,0,20).get("items");
        var paused=cards.stream().filter(c->c.get("id").equals(3L)).findFirst().orElseThrow();
        assertThat(paused.get("writeRate")).isNull();assertThat(paused.get("lagMs")).isNull();
        assertThatThrownBy(()->service.snapshot("bad",false,0,20)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void counterResetsKeepValidIncrementsAndTrendIsBounded() {
        var s=new MonitorService.Stats();var now=OffsetDateTime.now();var start=now.minusHours(1);
        s.accept(now.minusMinutes(5),100,0,2,start,start,60000);
        s.accept(now.minusMinutes(4),120,0,2,start,start,60000);
        s.accept(now.minusMinutes(3),5,0,2,start,start,60000);
        s.accept(now.minusMinutes(2),15,0,2,start,start,60000);
        assertThat(s.hourRows).isEqualTo(35);
        assertThat(s.hourSamples).isEqualTo(4);
        for(int i=0;i<1000;i++)s.accept(now,15,0,2,start,start,60000);
        assertThat(s.buckets).hasSize(5);
        assertThat(s.trend(60000)).hasSize(5);
    }
    @Test void samplesOutsideHourOnlyProvideBaseline() {
        var s=new MonitorService.Stats();var now=OffsetDateTime.now();var start=now.minusHours(1);
        s.accept(start.minusSeconds(30),100,0,2,start,now.minusMinutes(5),60000);
        s.accept(start.plusSeconds(30),110,0,2,start,now.minusMinutes(5),60000);
        s.accept(now,120,0,2,start,now.minusMinutes(5),60000);
        assertThat(s.hourRows).isEqualTo(20);
        assertThat(s.hourSamples).isEqualTo(2);
        assertThat(s.buckets).hasSize(1);
    }
}
