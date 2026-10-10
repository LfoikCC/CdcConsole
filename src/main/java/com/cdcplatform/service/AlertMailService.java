package com.cdcplatform.service;

import com.cdcplatform.model.Types.AlertType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.AuthenticationFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AlertMailService {
    public record Config(boolean enabled, String host, int port, String secure, String username,
                         String password, String fromName, String fromEmail, boolean passwordConfigured) {}
    public record Rule(Long id, String name, String description, boolean enabled, List<String> recipients,
                       List<String> alertTypes, String scope, List<Long> taskIds, String frequency) {}
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AtomicBoolean sending=new AtomicBoolean();
    public AlertMailService(JdbcClient jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public Config config(boolean secret) {
        return jdbc.sql("SELECT * FROM mail_config WHERE id=1").query((rs,n)->new Config(rs.getBoolean("enabled"),rs.getString("host"),rs.getInt("port"),rs.getString("secure"),rs.getString("username"),secret?rs.getString("password"):"",rs.getString("from_name"),rs.getString("from_email"),!rs.getString("password").isEmpty())).single();
    }
    public Config save(Config value) {
        if(value.port()<1||value.port()>65535||!Set.of("none","ssl","starttls").contains(value.secure())) throw new IllegalArgumentException("SMTP 端口或加密方式无效");
        validateTransport(value.host(), value.port(), value.secure());
        if(value.enabled()) { if(value.host()==null||value.host().isBlank()) throw new IllegalArgumentException("请填写 SMTP 服务器"); address(value.fromEmail()); }
        String password=value.password()==null||value.password().isEmpty()?config(true).password():value.password();
        jdbc.sql("UPDATE mail_config SET enabled=:e,host=:h,port=:p,secure=:s,username=:u,password=:w,from_name=:n,from_email=:f WHERE id=1")
            .param("e",value.enabled()).param("h",Objects.toString(value.host(),"").trim()).param("p",value.port()).param("s",value.secure()).param("u",Objects.toString(value.username(),"")).param("w",password).param("n",Objects.toString(value.fromName(),"")).param("f",Objects.toString(value.fromEmail(),"").trim()).update();
        return config(false);
    }
    static void address(String value) {
        try { var address=new InternetAddress(value,true); address.validate(); if(!address.getAddress().equals(value)||value.contains("\n")||value.contains("\r")||!value.contains("@")) throw new Exception(); }
        catch(Exception e) { throw new IllegalArgumentException("邮箱地址无效"); }
    }
    public List<Rule> rules() { return jdbc.sql("SELECT id,config_json FROM mail_rule ORDER BY id DESC").query((rs,n)->{var r=decode(rs.getString(2));return withId(r,rs.getLong(1));}).list(); }
    private Rule withId(Rule r,Long id) { return new Rule(id,r.name(),r.description(),r.enabled(),r.recipients(),r.alertTypes(),r.scope(),r.taskIds(),r.frequency()); }
    private Rule decode(String value) { try{return json.readValue(value,Rule.class);}catch(Exception e){throw new IllegalStateException("邮件规则读取失败",e);} }
    private String encode(Rule rule) { try{return json.writeValueAsString(rule);}catch(Exception e){throw new IllegalArgumentException("邮件规则无效",e);} }
    @Transactional
    public synchronized Rule saveRule(Long id, Rule r) {
        if(r.name()==null||r.name().isBlank()||r.name().length()>100||r.recipients()==null||r.recipients().isEmpty()||r.recipients().size()>50||r.alertTypes()==null||r.alertTypes().isEmpty()) throw new IllegalArgumentException("请填写规则名称、收件人和告警类型");
        r.recipients().forEach(AlertMailService::address); r.alertTypes().forEach(AlertType::valueOf);
        if(!Set.of("all","tasks").contains(r.scope())||!Set.of("immediate","5min","hourly").contains(r.frequency())) throw new IllegalArgumentException("订阅范围或频率无效");
        if("tasks".equals(r.scope())) {
            if(r.taskIds()==null||r.taskIds().isEmpty()) throw new IllegalArgumentException("请选择关联任务");
            for(Long task:r.taskIds()) if(jdbc.sql("SELECT COUNT(*) FROM sync_task WHERE id=:id").param("id",task).query(Long.class).single()==0) throw new IllegalArgumentException("关联任务不存在");
        }
        r=new Rule(id,r.name().trim(),Objects.toString(r.description(),""),r.enabled(),r.recipients().stream().distinct().toList(),r.alertTypes(),r.scope(),r.taskIds()==null?List.of():r.taskIds(),r.frequency());
        long cursor=jdbc.sql("SELECT COALESCE(MAX(id),0) FROM alert_event").query(Long.class).single();
        if(id==null) {
            id=jdbc.sql("SELECT id FROM FINAL TABLE (INSERT INTO mail_rule(config_json,alert_cursor) VALUES(:json,:cursor))").param("json",encode(r)).param("cursor",cursor).query(Long.class).single();
        } else {
            if(jdbc.sql("UPDATE mail_rule SET config_json=:json,alert_cursor=:cursor WHERE id=:id").param("json",encode(r)).param("cursor",cursor).param("id",id).update()==0) throw new IllegalArgumentException("订阅规则不存在");
            jdbc.sql("UPDATE mail_delivery SET status='CANCELLED' WHERE rule_id=:id AND status IN ('PENDING','FAILED')").param("id",id).update();
        }
        return withId(r,id);
    }
    @Transactional public void deleteRule(long id) {
        jdbc.sql("DELETE FROM mail_rule WHERE id=:id").param("id",id).update();
        jdbc.sql("UPDATE mail_delivery SET status='CANCELLED' WHERE rule_id=:id AND status IN ('PENDING','FAILED')").param("id",id).update();
    }
    private JavaMailSenderImpl sender(Config c) {
        if(c.host()==null||c.host().isBlank())throw new IllegalArgumentException("请先保存 SMTP 配置");
        var sender=new JavaMailSenderImpl();sender.setHost(c.host());sender.setPort(c.port());sender.setUsername(c.username());sender.setPassword(c.password());
        var p=sender.getJavaMailProperties();p.setProperty("mail.smtp.auth",String.valueOf(c.username()!=null&&!c.username().isBlank()));
        p.setProperty("mail.smtp.ssl.enable",String.valueOf("ssl".equals(c.secure())));p.setProperty("mail.smtp.starttls.enable",String.valueOf("starttls".equals(c.secure())));p.setProperty("mail.smtp.starttls.required",String.valueOf("starttls".equals(c.secure())));
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        // QQ's SMTP endpoint may send only its leaf certificate. Keep hostname
        // verification enabled while trusting the configured SMTP host so a
        // missing intermediate CA in the bundled JDK does not look like a
        // port/encryption error to the operator.
        if (("ssl".equals(c.secure()) || "starttls".equals(c.secure()))
                && "smtp.qq.com".equalsIgnoreCase(c.host())) {
            p.setProperty("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
            p.setProperty("mail.smtp.ssl.trust", c.host());
        }
        for(String timeout:List.of("connectiontimeout","timeout","writetimeout"))p.setProperty("mail.smtp."+timeout,"10000");return sender;
    }
    static void validateTransport(String host, int port, String secure) {
        if (port < 1 || port > 65535 || secure == null || !Set.of("none", "ssl", "starttls").contains(secure))
            throw new IllegalArgumentException("SMTP 端口或加密方式无效");
    }
    static String mailFailure(Exception error) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var causes=new ArrayList<Throwable>();
        var pending=new ArrayDeque<Throwable>();pending.add(error);
        while(!pending.isEmpty()) {
            var cause=pending.removeFirst();if(!seen.add(cause))continue;
            causes.add(cause);
            if(cause.getCause()!=null)pending.add(cause.getCause());
            if(cause instanceof jakarta.mail.MessagingException mail && mail.getNextException()!=null)pending.add(mail.getNextException());
        }
        if(causes.stream().anyMatch(c->c instanceof java.security.cert.CertPathBuilderException || c instanceof java.security.cert.CertPathValidatorException))
            return "SMTP 证书验证失败：服务器证书链不完整或当前 Java 信任库缺少签发 CA，请检查服务器证书链及 Java 信任库（PKIX）";
        Throwable current=error;
        while(current!=null) {
            if(current instanceof AuthenticationFailedException || current instanceof MailAuthenticationException)
                return "SMTP 认证失败：QQ 邮箱请使用授权码，不是邮箱登录密码";
            if(current instanceof javax.net.ssl.SSLException)
                return "SMTP TLS 握手失败：请检查端口与加密方式（465 用 SSL/TLS，587 用 STARTTLS）";
            if(current instanceof java.net.ConnectException || current instanceof java.net.SocketTimeoutException)
                return "SMTP 网络连接失败：请检查服务器地址、端口和防火墙";
            current=current.getCause();
        }
        return "SMTP 连接失败：请检查服务器地址、端口、加密方式、授权码和网络";
    }
    public void testConnection() { testConnection(null); }
    public void testConnection(Config draft) {
        try {
            var saved=config(true);var c=draft==null?saved:draft;
            if(draft!=null && (c.password()==null || c.password().isEmpty()))
                c=new Config(c.enabled(),c.host(),c.port(),c.secure(),c.username(),saved.password(),c.fromName(),c.fromEmail(),saved.passwordConfigured());
            validateTransport(c.host(),c.port(),c.secure());sender(c).testConnection();
        } catch(IllegalArgumentException e) { throw e; } catch(Exception e) { throw new IllegalStateException(mailFailure(e)); }
    }
    public void testMail(String recipient) { address(recipient); Config c=config(true);if(!c.enabled())throw new IllegalArgumentException("请先启用并保存邮件服务");enqueue(null,null,recipient,"[CDC] 测试邮件","CDC 邮件服务测试。",OffsetDateTime.now()); }
    private void enqueue(Long rule,Long alert,String recipient,String subject,String body,OffsetDateTime due) {
        jdbc.sql("INSERT INTO mail_delivery(rule_id,alert_id,recipient,subject,body,due_at) VALUES(:r,:a,:to,:s,:b,:d)").param("r",rule).param("a",alert).param("to",recipient).param("s",subject).param("b",body).param("d",due).update();
    }
    static OffsetDateTime due(String frequency,OffsetDateTime now) {
        if("hourly".equals(frequency))return now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        if("5min".equals(frequency))return now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(now.getMinute()%5).plusMinutes(5);
        return now;
    }
    @Scheduled(fixedDelay=5000) @Transactional
    public synchronized void collect() {
        for(Rule r:rules()) {
            long cursor=jdbc.sql("SELECT alert_cursor FROM mail_rule WHERE id=:id").param("id",r.id()).query(Long.class).single();
            // A creation cutoff plus a durable seen set also catches out-of-order commits.
            var alerts=jdbc.sql("SELECT a.*,t.name task_name FROM alert_event a LEFT JOIN sync_task t ON a.task_id=t.id WHERE a.id>:cursor AND NOT EXISTS (SELECT 1 FROM mail_rule_seen s WHERE s.rule_id=:rule AND s.alert_id=a.id) ORDER BY a.id LIMIT 500").param("cursor",cursor).param("rule",r.id()).query().listOfRows();
            for(var a:alerts) {
                long aid=((Number)a.get("ID")).longValue();long task=((Number)a.get("TASK_ID")).longValue();
                if(r.enabled()&&r.alertTypes().contains(a.get("ALERT_TYPE"))&&("all".equals(r.scope())||r.taskIds().contains(task))) {
                    String body="任务："+a.get("TASK_NAME")+" (#"+task+")\n类型："+a.get("ALERT_TYPE")+"\n时间："+a.get("CREATED_AT")+"\n告警："+a.get("TITLE")+"\n"+a.get("MESSAGE");
                    for(String recipient:r.recipients())enqueue(r.id(),aid,recipient,"[CDC] "+a.get("TITLE"),body,due(r.frequency(),OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))));
                }
                jdbc.sql("INSERT INTO mail_rule_seen(rule_id,alert_id) VALUES(:rule,:alert)").param("rule",r.id()).param("alert",aid).update();
            }
        }
    }
    @Scheduled(fixedDelay=3000)
    public void deliver() {
        if(!sending.compareAndSet(false,true))return;
        Thread.startVirtualThread(()->{try{sendPending();}finally{sending.set(false);}});
    }
    void sendPending() {
        Config c=config(true);if(!c.enabled())return;
        var rows=jdbc.sql("SELECT * FROM mail_delivery WHERE status='PENDING' AND due_at<=CURRENT_TIMESTAMP ORDER BY due_at,id LIMIT 200").query((rs,n)->{
            var row=new LinkedHashMap<String,Object>();row.put("ID",rs.getLong("id"));row.put("RULE_ID",rs.getObject("rule_id"));row.put("RECIPIENT",rs.getString("recipient"));row.put("SUBJECT",rs.getString("subject"));row.put("BODY",rs.getString("body"));row.put("DUE_AT",rs.getObject("due_at"));row.put("ATTEMPTS",rs.getInt("attempts"));return (Map<String,Object>)row;
        }).list();
        var groups=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var row:rows)groups.computeIfAbsent(row.get("RULE_ID")+"|"+row.get("RECIPIENT")+"|"+row.get("DUE_AT"),k->new ArrayList<>()).add(row);
        for(var group:groups.values()) {
            String error=null;
            try { var sender=sender(c);var message=sender.createMimeMessage();var helper=new MimeMessageHelper(message,"UTF-8");helper.setFrom(c.fromEmail(),c.fromName());helper.setTo((String)group.getFirst().get("RECIPIENT"));helper.setSubject(group.size()==1?(String)group.getFirst().get("SUBJECT"):"[CDC] 告警汇总（"+group.size()+" 条）");helper.setText(String.join("\n\n----------------\n\n",group.stream().map(row->(String)row.get("BODY")).toList()),false);sender.send(message); }
            catch(Exception e) { error="SMTP 发送失败（"+e.getClass().getSimpleName()+"），请检查邮件服务配置和网络"; }
            for(var row:group) {
                int attempts=((Number)row.get("ATTEMPTS")).intValue()+1;
                jdbc.sql("UPDATE mail_delivery SET status=:s,attempts=:a,last_error=:e,sent_at=:sent,due_at=:due WHERE id=:id")
                  .param("s",error==null?"SENT":attempts>=6?"FAILED":"PENDING").param("a",attempts).param("e",error).param("sent",error==null?OffsetDateTime.now():null).param("due",OffsetDateTime.now().plusSeconds(30L*(1L<<Math.min(attempts,6)))).param("id",row.get("ID")).update();
            }
        }
    }
    public Map<String,Object> history(int page,int size) {
        page=Math.max(0,page);size=Math.max(1,Math.min(size,100));return Map.of("items",jdbc.sql("SELECT id,rule_id,alert_id,recipient,subject,status,attempts,created_at,sent_at,last_error FROM mail_delivery ORDER BY id DESC LIMIT :size OFFSET :offset").param("size",size).param("offset",page*size).query().listOfRows(),"total",jdbc.sql("SELECT COUNT(*) FROM mail_delivery").query(Long.class).single());
    }
    public void retry(long id) { jdbc.sql("UPDATE mail_delivery SET status='PENDING',attempts=0,due_at=CURRENT_TIMESTAMP WHERE id=:id AND status='FAILED'").param("id",id).update(); }
}
