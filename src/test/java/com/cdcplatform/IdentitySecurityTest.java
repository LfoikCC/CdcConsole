package com.cdcplatform;

import com.cdcplatform.service.ConsoleIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:identity-security;DB_CLOSE_DELAY=-1","cdc.cache.directory=target/identity-security-cache"})
@AutoConfigureMockMvc
class IdentitySecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ConsoleIdentityService users;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    private Cookie cookie(ConsoleIdentityService.Session s){return new Cookie("CDC_SESSION",s.id());}
    @Test void loginCsrfRoleEnforcementRevocationAndAudit()throws Exception{
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/runtime/events")).andExpect(status().isUnauthorized());
        var anonymous=users.newSession(null);
        mvc.perform(post("/api/auth/login").cookie(cookie(anonymous)).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"admin123\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").cookie(cookie(anonymous)).header("X-CSRF-Token",anonymous.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"incorrect\"}")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/auth/login").cookie(cookie(anonymous)).header("X-CSRF-Token",anonymous.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("SameSite=Strict"))))
                .andExpect(jsonPath("$.role").value("ADMIN")).andExpect(jsonPath("$.password_hash").doesNotExist()).andReturn();
        assertNull(users.session(anonymous.id()));
        long viewer=users.create(Map.of("username","reader","name","只读测试","role","VIEWER","password","reader123"));
        long operator=users.create(Map.of("username","operator","name","普通测试","role","OPERATOR","password","operator123"));
        var readSession=users.newSession(viewer);var opSession=users.newSession(operator);
        mvc.perform(get("/api/tasks").cookie(cookie(readSession))).andExpect(status().isOk());
        mvc.perform(get("/api/audit-logs").cookie(cookie(readSession))).andExpect(status().isOk());
        mvc.perform(get("/api/users").cookie(cookie(readSession))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-logs/export").cookie(cookie(readSession))).andExpect(status().isForbidden());
        mvc.perform(post("/api/events").cookie(cookie(opSession)).header("X-CSRF-Token",opSession.csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks").cookie(cookie(opSession)).header("X-CSRF-Token",opSession.csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/alerts/999/resolve").cookie(cookie(readSession)).header("X-CSRF-Token",readSession.csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/settings").cookie(cookie(opSession))).andExpect(status().isForbidden());
        users.update(operator,Map.of("name","改为只读","role","VIEWER"));
        mvc.perform(get("/api/tasks").cookie(cookie(opSession))).andExpect(status().isUnauthorized());
        users.action(viewer,"disable",Map.of());
        mvc.perform(get("/api/tasks").cookie(cookie(readSession))).andExpect(status().isUnauthorized());
        users.action(viewer,"delete",Map.of());
        assertThrows(IllegalArgumentException.class,()->users.create(Map.of("username","reader","name","重复","role","VIEWER","password","reader123")));
        var admin=users.authenticate("admin","admin123");
        assertThrows(IllegalArgumentException.class,()->users.action(admin.id(),"disable",Map.of()));
        assertThrows(IllegalArgumentException.class,()->users.update(admin.id(),Map.of("role","VIEWER","name","管理员")));
        users.bootstrap();assertNotNull(users.authenticate("admin","admin123"));
        assertTrue(db.sql("SELECT COUNT(*) FROM console_audit WHERE result='FAILED'").query(Long.class).single()>0);
        String stored=db.sql("SELECT LISTAGG(COALESCE(before_json,'') || COALESCE(after_json,'') || COALESCE(reason,''),' ') FROM console_audit").query(String.class).single();
        assertFalse(stored.contains("admin123"));assertFalse(stored.contains("password_hash"));assertFalse(stored.contains(anonymous.csrf()));
    }
}
