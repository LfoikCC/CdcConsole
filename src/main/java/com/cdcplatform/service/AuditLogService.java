package com.cdcplatform.service;
import jakarta.servlet.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
@Service
public class AuditLogService {
 private final JdbcClient db;private final ObjectMapper json;private final int days;
 public AuditLogService(JdbcClient db,ObjectMapper json,@Value("${cdc.audit.retention-days:180}")int days){this.db=db;this.json=json;this.days=days;}
 public Object before(HttpServletRequest req){
  if(Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())||req.getAttribute("audit-user")==null)return null;
  var parts=req.getRequestURI().split("/");try{
   if(parts.length>=4&&parts[2].equals("users"))return db.sql("SELECT username,name,email,role,enabled,deleted FROM console_user WHERE id=:id").param("id",Long.parseLong(parts[3])).query().listOfRows().stream().findFirst().orElse(null);
   if(parts.length>=4&&parts[2].equals("tasks")){
    long id=Long.parseLong(parts[3]);var value=new LinkedHashMap<String,Object>();
    value.put("task",db.sql("SELECT name,description,status,source_id,sync_mode,time_zone,ddl_policy,alert_lag_seconds,reconciliation_cron FROM sync_task WHERE id=:id").param("id",id).query().listOfRows());
    value.put("tables",db.sql("SELECT source_database,source_schema,source_table,target_schema,target_table,columns_json,key_columns_json,reconciliation_json FROM sync_task_table WHERE task_id=:id ORDER BY id").param("id",id).query().listOfRows());
    value.put("subscriptions",db.sql("SELECT name,target_id,description,status FROM task_target_subscription WHERE task_id=:id ORDER BY id").param("id",id).query().listOfRows());return value;
   }
   if(parts.length>=4&&parts[2].equals("sources"))return db.sql("SELECT name,db_type,host,port,database_name,username,poll_interval_ms,enabled FROM cdc_source WHERE id=:id").param("id",Long.parseLong(parts[3])).query().listOfRows();
   if(parts.length>=4&&parts[2].equals("targets"))return db.sql("SELECT name,target_type,config_json,enabled FROM cdc_target WHERE id=:id").param("id",Long.parseLong(parts[3])).query().listOfRows();
   if(parts.length>=4&&parts[2].equals("mail")&&parts[3].equals("config"))return db.sql("SELECT enabled,host,port,secure,username,from_name,from_email FROM mail_config WHERE id=1").query().singleRow();
   if(parts.length>=5&&parts[2].equals("mail")&&parts[3].equals("rules"))return db.sql("SELECT config_json FROM mail_rule WHERE id=:id").param("id",Long.parseLong(parts[4])).query().listOfRows();
   if(parts.length>=3&&parts[2].equals("settings"))return db.sql("SELECT setting_key,setting_value FROM app_setting").query().listOfRows();
  }catch(Exception ignored){}return null;
 }
 public void recordRequest(HttpServletRequest req,HttpServletResponse res,Object before){
  String p=req.getRequestURI();if(p.equals("/api/auth/me")&&res.getStatus()==401)return;boolean export=p.equals("/api/audit-logs/export");if((req.getMethod().equals("GET")&&!export&&res.getStatus()<400)||p.equals("/api/tasks/validate")||p.endsWith("/precheck"))return;
  var u=(ConsoleIdentityService.User)req.getAttribute("audit-user");String[] parts=p.split("/");String type=parts.length>2?parts[2]:"system",id=parts.length>3?parts[3]:"";
  String action=switch(req.getMethod()){case "POST"->"CREATE";case "DELETE"->"DELETE";case "GET"->"READ";default->"EDIT";};
  if(p.contains("/actions/"))action=p.substring(p.lastIndexOf('/')+1).toUpperCase(Locale.ROOT);
  if(p.startsWith("/api/auth/"))action=id.toUpperCase(Locale.ROOT);if(p.endsWith("/retry"))action="RETRY";if(p.endsWith("/reconciliations"))action="RECONCILE";if(p.endsWith("/recoveries"))action="RERUN";if(export)action="EXPORT";
  if(u==null&&req.getAttribute("audit-actor") instanceof String actor&&!actor.isBlank())u=new ConsoleIdentityService.User(0,actor,"","","",false,null);
  record(u,action,type,id,p,res.getStatus()<400?"SUCCESS":"FAILED",req.getRemoteAddr(),String.valueOf(req.getAttribute("request-id")),req.getMethod()+" "+p+" · HTTP "+res.getStatus(),before,res.getStatus()<400?before(req):null,null);
 }
 public void record(ConsoleIdentityService.User user,String action,String type,String id,String name,String result,String ip,String requestId,String reason,Object before,Object after,String recovery){
  db.sql("INSERT INTO console_audit(actor_id,actor,action,resource_type,resource_id,resource_name,result,ip,request_id,reason,before_json,after_json,recovery_id) VALUES(:uid,:actor,:action,:type,:id,:name,:result,:ip,:request,:reason,:before,:after,:recovery)")
   .param("uid",user==null||user.id()==0?null:user.id()).param("actor",user==null?"未登录":user.username()).param("action",action).param("type",type).param("id",id).param("name",name).param("result",result).param("ip",ip).param("request",requestId)
   .param("reason",reason==null?null:reason.substring(0,Math.min(2000,reason.length()))).param("before",write(before)).param("after",write(after)).param("recovery",recovery).update();
 }
 private String write(Object value){try{return value==null?null:json.writeValueAsString(sanitize(value));}catch(Exception e){return null;}}
 private Object sanitize(Object value)throws Exception{
  if(value instanceof java.sql.Clob c)return sanitize(c.getSubString(1,(int)c.length()));
  if(value instanceof Map<?,?> map){var safe=new LinkedHashMap<String,Object>();for(var e:map.entrySet()){String k=String.valueOf(e.getKey());safe.put(k,k.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|cookie|authorization|credential).*")?"***":sanitize(e.getValue()));}return safe;}
  if(value instanceof Collection<?> items){var safe=new ArrayList<Object>();for(var item:items)safe.add(sanitize(item));return safe;}
  if(value instanceof String s&&(s.startsWith("{")||s.startsWith("[")))try{return sanitize(json.readValue(s,Object.class));}catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
  return value;
 }
 public Map<String,Object> summary(){return db.sql("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN action IN ('STOP','DELETE','PAUSE','RERUN','REPLAY','RESET-PASSWORD') THEN 1 ELSE 0 END),0) AS critical,COALESCE(SUM(CASE WHEN result='FAILED' THEN 1 ELSE 0 END),0) AS failed,COUNT(DISTINCT actor_id) AS actors FROM console_audit WHERE occurred_at>=:today").param("today",LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime()).query().singleRow();}
 public Map<String,Object> page(Map<String,String> p){var f=filter(p);int page=integer(p,"page",0,1000000),size=integer(p,"size",50,200);long total=db.sql("SELECT COUNT(*) FROM console_audit WHERE "+f.sql).params(f.values).query(Long.class).single();var rows=db.sql("SELECT * FROM console_audit WHERE "+f.sql+" ORDER BY occurred_at DESC,id DESC LIMIT :size OFFSET :offset").params(f.values).param("size",size).param("offset",(long)page*size).query().listOfRows();return Map.of("items",rows,"total",total,"page",page,"size",size,"totalPages",(total+size-1)/size);}
 public Map<String,Object> detail(long id){return db.sql("SELECT * FROM console_audit WHERE id=:id").param("id",id).query().singleRow();}
 public void export(Map<String,String> p,java.io.Writer w)throws java.io.IOException{var f=filter(p);w.write("\ufeff时间,操作人,操作,资源类型,资源,结果,来源IP,原因\n");long cursor=Long.MAX_VALUE;while(true){var rows=db.sql("SELECT * FROM console_audit WHERE "+f.sql+" AND id<:cursor ORDER BY id DESC LIMIT 500").params(f.values).param("cursor",cursor).query().listOfRows();if(rows.isEmpty())break;for(var row:rows){for(String k:List.of("OCCURRED_AT","ACTOR","ACTION","RESOURCE_TYPE","RESOURCE_NAME","RESULT","IP","REASON")){if(!k.equals("OCCURRED_AT"))w.write(',');w.write(csv(row.get(k)));}w.write('\n');cursor=((Number)row.get("ID")).longValue();}w.flush();}}
 private String csv(Object v){String s=Objects.toString(v,"");if(s.matches("^[\\s]*[=+@-].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
 private record Filter(String sql,Map<String,Object> values){}
 private Filter filter(Map<String,String>p){StringBuilder sql=new StringBuilder("1=1");var v=new HashMap<String,Object>();for(String k:List.of("actor","action","result","resource_type")){String s=p.get(k);if(s!=null&&!s.isBlank()){sql.append(" AND ").append(k).append("=:"+k);v.put(k,s);}}for(String k:List.of("from","to"))if(p.containsKey(k)&&!p.get(k).isBlank()){sql.append(" AND occurred_at").append(k.equals("from")?">=":"<").append(":"+k);v.put(k,OffsetDateTime.parse(p.get(k)));}if(!p.getOrDefault("q","").isBlank()){sql.append(" AND LOWER(CONCAT(actor,' ',resource_name,' ',reason)) LIKE :q");v.put("q","%"+p.get("q").toLowerCase(Locale.ROOT)+"%");}return new Filter(sql.toString(),v);}
 private int integer(Map<String,String>p,String k,int d,int max){try{return Math.max(k.equals("size")?1:0,Math.min(max,Integer.parseInt(p.getOrDefault(k,""+d))));}catch(Exception e){return d;}}
 @Scheduled(cron="0 0 3 * * *")void purge(){db.sql("DELETE FROM console_audit WHERE occurred_at<:cutoff").param("cutoff",OffsetDateTime.now().minusDays(days)).update();}
}
