package com.cdcplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AlertMailServiceTest {
    JdbcClient jdbc;
    AlertMailService service;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:mail_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(ds).load().migrate();jdbc=JdbcClient.create(ds);service=new AlertMailService(jdbc,new ObjectMapper());
        jdbc.sql("INSERT INTO cdc_source(id,name,db_type,host,port,database_name,username,password) VALUES(1,'source','MYSQL','localhost',3306,'db','user','')").update();
        jdbc.sql("INSERT INTO cdc_target(id,name,target_type,config_json) VALUES(1,'target','MYSQL','{}')").update();
        jdbc.sql("INSERT INTO sync_task(id,name,source_id,target_id,sync_mode) VALUES(1,'task',1,1,'INCREMENTAL_ONLY')").update();
    }
    @Test void passwordsAreMaskedAndBlankUpdatesPreserveThem() {
        service.save(new AlertMailService.Config(false,"localhost",587,"starttls","user","secret","CDC","cdc@example.com",false));
        assertThat(service.config(false).password()).isEmpty();assertThat(service.config(false).passwordConfigured()).isTrue();
        service.save(new AlertMailService.Config(false,"localhost",587,"starttls","user","","CDC","cdc@example.com",false));
        assertThat(service.config(true).password()).isEqualTo("secret");
    }
    @Test void rulesOnlyQueueNewMatchingAlertsOnceAndDeleteCancelsPending() {
        jdbc.sql("INSERT INTO alert_event(id,task_id,alert_type,status,title,message) VALUES(100,1,'TABLE_FAILURE','OPEN','old','old')").update();
        var rule=service.saveRule(null,new AlertMailService.Rule(null,"ops","",true,List.of("ops@example.com"),List.of("TABLE_FAILURE"),"all",List.of(),"immediate"));
        jdbc.sql("INSERT INTO alert_event(id,task_id,alert_type,status,title,message) VALUES(101,1,'TABLE_FAILURE','OPEN','new','details')").update();
        jdbc.sql("INSERT INTO alert_event(id,task_id,alert_type,status,title,message) VALUES(102,1,'DATA_DELAY','OPEN','other','details')").update();
        service.collect();service.collect();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mail_delivery").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT alert_id FROM mail_delivery").query(Long.class).single()).isEqualTo(101);
        jdbc.sql("INSERT INTO alert_event(id,task_id,alert_type,status,title,message) VALUES(104,1,'TABLE_FAILURE','OPEN','latest','details')").update();service.collect();
        jdbc.sql("INSERT INTO alert_event(id,task_id,alert_type,status,title,message) VALUES(103,1,'TABLE_FAILURE','OPEN','late commit','details')").update();service.collect();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mail_delivery").query(Long.class).single()).isEqualTo(3);
        service.deleteRule(rule.id());assertThat(jdbc.sql("SELECT DISTINCT status FROM mail_delivery").query(String.class).single()).isEqualTo("CANCELLED");
    }
    @Test void validatesRecipientsAndCalculatesClosedAggregationBoundaries() {
        assertThatThrownBy(()->AlertMailService.address("bad\r\nBcc: bad@example.com")).isInstanceOf(IllegalArgumentException.class);
        var now=OffsetDateTime.parse("2026-10-09T23:58:21+08:00");
        assertThat(AlertMailService.due("5min",now)).isEqualTo("2026-10-10T00:00:00+08:00");
        assertThat(AlertMailService.due("hourly",now)).isEqualTo("2026-10-10T00:00:00+08:00");
        assertThatThrownBy(()->service.testMail("ops@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->AlertMailService.validateTransport("smtp.qq.com",2525,"ssl")).doesNotThrowAnyException();
        assertThatCode(()->AlertMailService.validateTransport("smtp.qq.com",587,"starttls")).doesNotThrowAnyException();
    }
    @Test void smtpFailureIsRetriedAndEventuallyRecordedWithoutThrowingIntoCdc() throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        service.save(new AlertMailService.Config(true,"127.0.0.1",port,"none","","","CDC","cdc@example.com",false));
        service.testMail("ops@example.com");
        service.sendPending();
        assertThat(jdbc.sql("SELECT attempts FROM mail_delivery").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM mail_delivery").query(String.class).single()).isEqualTo("PENDING");
        jdbc.sql("UPDATE mail_delivery SET attempts=5,due_at=CURRENT_TIMESTAMP").update();service.sendPending();
        assertThat(jdbc.sql("SELECT status FROM mail_delivery").query(String.class).single()).isEqualTo("FAILED");
        service.retry(jdbc.sql("SELECT id FROM mail_delivery").query(Long.class).single());
        assertThat(jdbc.sql("SELECT attempts FROM mail_delivery").query(Integer.class).single()).isZero();
    }
    @Test void certificateFailureIsNotReportedAsPortMismatch() {
        var ssl=new javax.net.ssl.SSLHandshakeException("certificate_unknown");
        ssl.initCause(new java.security.cert.CertPathBuilderException("PKIX"));
        var mail=new jakarta.mail.MessagingException("TLS failed",ssl);
        assertThat(AlertMailService.mailFailure(mail)).contains("证书", "PKIX");
    }
    @Test void connectionDraftDoesNotOverwriteSavedConfiguration() throws Exception {
        service.save(new AlertMailService.Config(false,"saved.example.com",465,"ssl","user","secret","CDC","cdc@example.com",false));
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        var draft=new AlertMailService.Config(false,"127.0.0.1",port,"none","","","CDC","cdc@example.com",false);
        assertThatThrownBy(()->service.testConnection(draft)).hasMessageContaining("网络");
        assertThat(service.config(false).host()).isEqualTo("saved.example.com");
    }
    @Test void localSmtpAcceptsAndRecordsATestMessage() throws Exception {
        try(var server=new java.net.ServerSocket(0)) {
            var received=new java.util.concurrent.CompletableFuture<String>();
            Thread.startVirtualThread(()->{
                try(var client=server.accept();var input=new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));var out=new java.io.PrintWriter(client.getOutputStream(),true)) {
                    client.setSoTimeout(5000);out.print("220 localhost SMTP\r\n");out.flush();String line;boolean data=false;var body=new StringBuilder();
                    while((line=input.readLine())!=null) {
                        if(data){if(line.equals(".")){data=false;out.print("250 accepted\r\n");}else body.append(line).append('\n');}
                        else if(line.startsWith("DATA")){out.print("354 continue\r\n");data=true;}
                        else if(line.startsWith("QUIT")){out.print("221 bye\r\n");out.flush();break;}
                        else out.print("250 OK\r\n");
                        out.flush();
                    }
                    received.complete(body.toString());
                }catch(Exception e){received.completeExceptionally(e);}
            });
            service.save(new AlertMailService.Config(true,"127.0.0.1",server.getLocalPort(),"none","","","CDC","cdc@example.com",false));
            service.testMail("ops@example.com");service.sendPending();
            assertThat(received.get(5,java.util.concurrent.TimeUnit.SECONDS)).contains("To: ops@example.com");
            assertThat(jdbc.sql("SELECT status FROM mail_delivery").query(String.class).single()).isEqualTo("SENT");
        }
    }
}
