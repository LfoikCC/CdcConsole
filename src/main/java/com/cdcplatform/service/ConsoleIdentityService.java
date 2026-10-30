package com.cdcplatform.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ConsoleIdentityService {
    public record User(long id, String username, String name, String email, String role, boolean enabled, OffsetDateTime lastLoginAt) {}
    public record Session(String id, String csrf, Long userId, OffsetDateTime expiresAt) {}
    private final JdbcClient db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final org.springframework.context.ApplicationEventPublisher events;
    public record SessionsRevoked(List<String> sessions){}
    public ConsoleIdentityService(JdbcClient db,org.springframework.context.ApplicationEventPublisher events) { this.db=db;this.events=events; }
    @PostConstruct
    @Transactional
    public void bootstrap() {
        if(db.sql("SELECT COUNT(*) FROM console_bootstrap").query(Long.class).single()==0) {
            if(db.sql("SELECT COUNT(*) FROM console_user").query(Long.class).single()==0)
                db.sql("INSERT INTO console_user(username,name,role,password_hash) VALUES('admin','管理员','ADMIN',:hash)")
                        .param("hash",passwords.encode("admin123")).update();
            db.sql("INSERT INTO console_bootstrap(id) VALUES(1)").update();
        }
    }
    public Session newSession(Long userId) {
        var s=new Session(random(),random(),userId,OffsetDateTime.now().plusHours(8));
        db.sql("INSERT INTO console_session(id,user_id,csrf,expires_at) VALUES(:id,:user,:csrf,:expires)")
                .param("id",s.id()).param("user",userId).param("csrf",s.csrf()).param("expires",s.expiresAt()).update();
        return s;
    }
    public Session session(String id) {
        if(id==null) return null;
        return db.sql("SELECT * FROM console_session WHERE id=:id AND expires_at>CURRENT_TIMESTAMP").param("id",id)
                .query((rs,n)->new Session(rs.getString("id"),rs.getString("csrf"),(Long)rs.getObject("user_id"),rs.getObject("expires_at",OffsetDateTime.class))).optional().orElse(null);
    }
    public void revoke(String id) { if(id!=null){db.sql("DELETE FROM console_session WHERE id=:id").param("id",id).update();events.publishEvent(new SessionsRevoked(List.of(id)));} }
    public void revokeUser(long id) { var revoked=db.sql("SELECT id FROM console_session WHERE user_id=:id").param("id",id).query(String.class).list();db.sql("DELETE FROM console_session WHERE user_id=:id").param("id",id).update();events.publishEvent(new SessionsRevoked(revoked)); }
    public User user(long id) {
        return db.sql("SELECT * FROM console_user WHERE id=:id AND deleted=FALSE").param("id",id).query(this::map).optional()
                .orElseThrow(()->new IllegalArgumentException("用户不存在"));
    }
    public User sessionUser(Session session) {
        if(session==null||session.userId()==null) return null;
        try { var u=user(session.userId()); return u.enabled()?u:null; } catch(IllegalArgumentException e) {return null;}
    }
    public User authenticate(String username,String password) {
        var rows=db.sql("SELECT * FROM console_user WHERE username=:u AND deleted=FALSE").param("u",Objects.toString(username,"")).query().listOfRows();
        // Use an actual BCrypt verification even for an unknown username.
        String hash=rows.isEmpty()?"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy":String.valueOf(rows.getFirst().get("PASSWORD_HASH"));
        boolean matched=passwords.matches(Objects.toString(password,""),hash);
        if(rows.isEmpty()||!matched||!Boolean.TRUE.equals(rows.getFirst().get("ENABLED"))) return null;
        long id=((Number)rows.getFirst().get("ID")).longValue();
        db.sql("UPDATE console_user SET last_login_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",id).update();
        return user(id);
    }
    public List<User> list() { return db.sql("SELECT * FROM console_user WHERE deleted=FALSE ORDER BY id").query(this::map).list(); }
    @Transactional
    public synchronized long create(Map<String,Object> body) {
        String username=text(body,"username");
        if(!username.matches("[A-Za-z0-9_.-]{3,80}")) throw new IllegalArgumentException("用户名须为3至80位字母、数字、点、下划线或短横线");
        var hash=passwords.encode(password(Objects.toString(body.get("password"),"")));
        if(db.sql("SELECT COUNT(*) FROM console_user WHERE username=:u").param("u",username).query(Long.class).single()>0) throw new IllegalArgumentException("用户名已存在或曾被使用");
        db.sql("INSERT INTO console_user(username,name,email,role,password_hash,enabled) VALUES(:u,:n,:e,:r,:p,:enabled)")
                .param("u",username).param("n",name(body)).param("e",email(body)).param("r",role(body))
                .param("p",hash).param("enabled",!Boolean.FALSE.equals(body.get("enabled"))).update();
        return db.sql("SELECT id FROM console_user WHERE username=:u").param("u",username).query(Long.class).single();
    }
    @Transactional
    public synchronized void update(long id,Map<String,Object> body) {
        lockAdministrators();
        var current=user(id); String role=role(body); boolean enabled=!Boolean.FALSE.equals(body.get("enabled"));
        if(body.containsKey("username")&&!current.username().equals(text(body,"username"))) throw new IllegalArgumentException("用户名不可修改");
        protectAdmin(current,enabled&&role.equals("ADMIN"));
        db.sql("UPDATE console_user SET name=:n,email=:e,role=:r,enabled=:enabled WHERE id=:id")
                .param("n",name(body)).param("e",email(body)).param("r",role).param("enabled",enabled).param("id",id).update();
        revokeUser(id);
    }
    @Transactional
    public synchronized void action(long id,String action,Map<String,Object> body) {
        lockAdministrators();
        var u=user(id);
        switch(action) {
            case "enable","disable","delete" -> {
                boolean enabled=action.equals("enable"); protectAdmin(u,enabled&&u.role().equals("ADMIN"));
                db.sql("UPDATE console_user SET enabled=:e,deleted=:d WHERE id=:id").param("e",enabled).param("d",action.equals("delete")).param("id",id).update();
            }
            case "reset-password" -> setPassword(id,Objects.toString(body.get("password"),""));
            default -> throw new IllegalArgumentException("用户操作无效");
        }
        revokeUser(id);
    }
    @Transactional
    public void changePassword(long id,String old,String next) {
        String hash=db.sql("SELECT password_hash FROM console_user WHERE id=:id").param("id",id).query(String.class).single();
        if(!passwords.matches(Objects.toString(old,""),hash)) throw new IllegalArgumentException("当前密码不正确");
        setPassword(id,next); revokeUser(id);
    }
    private void setPassword(long id,String value) { db.sql("UPDATE console_user SET password_hash=:p WHERE id=:id").param("p",passwords.encode(password(value))).param("id",id).update(); }
    private void protectAdmin(User user,boolean remainsAdmin) {
        if(user.enabled()&&user.role().equals("ADMIN")&&!remainsAdmin&&db.sql("SELECT COUNT(*) FROM console_user WHERE role='ADMIN' AND enabled=TRUE AND deleted=FALSE").query(Long.class).single()<=1)
            throw new IllegalArgumentException("不能删除、停用或降级最后一名管理员");
    }
    private void lockAdministrators(){db.sql("SELECT id FROM console_bootstrap WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    private String role(Map<String,Object> b) {String r=text(b,"role");if(!Set.of("ADMIN","OPERATOR","VIEWER").contains(r))throw new IllegalArgumentException("角色无效");return r;}
    private String name(Map<String,Object>b){String n=text(b,"name");if(n.isBlank()||n.length()>120)throw new IllegalArgumentException("请填写姓名（最多120字）");return n;}
    private String email(Map<String,Object>b){String e=text(b,"email");if(!e.isBlank()&&!e.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("邮箱格式不正确");return e;}
    private String password(String p){if(p.length()<8||p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||!p.matches("(?s).*[A-Za-z].*")||!p.matches("(?s).*[0-9].*"))throw new IllegalArgumentException("密码至少8位、最多72字节，包含字母和数字");return p;}
    private String text(Map<String,Object>b,String k){return Objects.toString(b.get(k),"").trim();}
    private User map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new User(r.getLong("id"),r.getString("username"),r.getString("name"),r.getString("email"),r.getString("role"),r.getBoolean("enabled"),r.getObject("last_login_at",OffsetDateTime.class));}
    private String random(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    @Scheduled(fixedDelay=60000) void purge(){db.sql("DELETE FROM console_session WHERE expires_at<CURRENT_TIMESTAMP").update();}
}
