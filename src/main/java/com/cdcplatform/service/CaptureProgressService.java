package com.cdcplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Durable connector offsets, including heartbeat offsets, recorded only after batch fsync. */
@Service
public class CaptureProgressService {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final ConnectionProbeService probe;
    public CaptureProgressService(JdbcClient db,ObjectMapper json,ConnectionProbeService probe){this.db=db;this.json=json;this.probe=probe;}
    /** Called before opening a connector or a new snapshot, never by the local status GET. */
    public void verifySource(long group,com.cdcplatform.model.SourceConfig source,String database)throws java.sql.SQLException{
        if(source.dbType()==com.cdcplatform.model.Types.DatabaseType.STARROCKS)return;
        String sql=source.dbType()==com.cdcplatform.model.Types.DatabaseType.MYSQL?"SELECT @@server_uuid":"SELECT CONVERT(varchar(128),SERVERPROPERTY('ServerName'))+':'+DB_NAME()";
        try(var c=java.sql.DriverManager.getConnection(probe.url(source,database),source.username(),source.password());var s=c.createStatement()){
            s.setQueryTimeout(10);
            try(var r=s.executeQuery(sql)){
                if(!r.next()||r.getString(1)==null)throw new IllegalStateException("无法验证源实例身份，不能沿用旧采集位点");
                String identity=r.getString(1),old=db.sql("SELECT source_identity FROM cdc_capture_group WHERE id=:id").param("id",group).query(String.class).optional().orElse(null);
                if(old!=null&&!old.equals(identity))throw new IllegalStateException("源身份已变化，拒绝继续使用原采集位点；请检查源实例配置");
                db.sql("UPDATE cdc_capture_group SET source_identity=:value WHERE id=:id AND source_identity IS NULL").param("value",identity).param("id",group).update();
            }
        }
    }
    public long generation(long group){return db.sql("SELECT generation FROM cdc_capture_group WHERE id=:id").param("id",group).query(Long.class).single();}
    public void reset(long group){db.sql("UPDATE cdc_capture_group SET generation=generation+1,durable_offset=NULL,heartbeat_at=NULL,capture_position_json=NULL WHERE id=:id").param("id",group).update();}
    public void committed(long group,Map<String,?> offset){
        if(offset==null||offset.isEmpty())return;
        try{db.sql("UPDATE cdc_capture_group SET durable_offset=:offset,heartbeat_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",group).param("offset",json.writeValueAsString(offset)).update();}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("无法保存采集进度",e);}
    }
    public boolean reached(long group,String fence){
        if(fence==null)return false;
        String value=db.sql("SELECT durable_offset FROM cdc_capture_group WHERE id=:id").param("id",group).query(String.class).optional().orElse(null);
        if(value==null)return false;
        try{return reached(json.readValue(value,Map.class),fence);}catch(Exception e){return false;}
    }
    static boolean reached(Map<String,?> offset,String fence){
        if(fence.startsWith("POLL:"))return offset.get("poll_started_at") instanceof Number n&&n.longValue()>=Long.parseLong(fence.substring(5));
        if(Boolean.TRUE.equals(offset.get("snapshot"))||"true".equals(offset.get("snapshot")))return false;
        if(fence.startsWith("MYSQL:")){
            String[] parts=fence.split(":");String file=String.valueOf(offset.getOrDefault("file",null));
            int dot=file.lastIndexOf('.'),expectedDot=parts[1].lastIndexOf('.');
            if(dot<0||expectedDot<0||!file.substring(0,dot).equals(parts[1].substring(0,expectedDot)))return false;
            try{long actual=Long.parseLong(file.substring(dot+1)),expected=Long.parseLong(parts[1].substring(expectedDot+1));
                return actual>expected||actual==expected&&Long.parseLong(String.valueOf(offset.get("pos")))>=Long.parseLong(parts[2]);
            }catch(NumberFormatException e){return false;}
        }
        if(fence.startsWith("SQLSERVER:")){
            String lsn=String.valueOf(offset.get("commit_lsn")).replace(":","").toLowerCase(java.util.Locale.ROOT);
            String expected=fence.substring(10).replace(":","").toLowerCase(java.util.Locale.ROOT);
            return lsn.matches("[0-9a-f]{20}")&&expected.matches("[0-9a-f]{20}")&&lsn.compareTo(expected)>=0;
        }
        return false;
    }
}
