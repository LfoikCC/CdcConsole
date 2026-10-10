package com.cdcplatform.service;

import com.cdcplatform.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:log-events-test;DB_CLOSE_DELAY=-1","cdc.cache.directory=target/test-log-events-cache","cdc.debezium.directory=target/test-log-events-offsets"})
class RuntimeLogEventsTest {
    @Autowired TaskRepository tasks;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean RuntimeEventService events;

    @Test void logNotificationIsSentAfterCommitAndNeverAfterRollback() {
        jdbc.sql("INSERT INTO cdc_source(id,name,db_type,host,port,database_name,username,password) VALUES(99001,'source','MYSQL','localhost',3306,'db','u','')").update();
        jdbc.sql("INSERT INTO cdc_target(id,name,target_type,config_json) VALUES(99001,'target','MYSQL','{}')").update();
        jdbc.sql("INSERT INTO sync_task(id,name,source_id,target_id,sync_mode,status) VALUES(99001,'test',99001,99001,'INCREMENTAL_ONLY','DRAFT')").update();
        clearInvocations(events);
        var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(status->{tasks.addLog(99001,null,"INFO","commit");verify(events,never()).publish(eq("task-log"),any());});
        verify(events).publish("task-log",java.util.Map.of("taskId",99001L));
        clearInvocations(events);
        tx.executeWithoutResult(status->{tasks.addLog(99001,null,"INFO","rollback");status.setRollbackOnly();});
        verify(events,never()).publish(eq("task-log"),any());
        tasks.addLog(99001,null,"INFO","autocommit");
        verify(events).publish("task-log",java.util.Map.of("taskId",99001L));
    }
}
