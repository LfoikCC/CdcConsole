package com.cdcplatform;

import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-api-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-task-api-cache"
})
@AutoConfigureMockMvc
class TaskApiTest {
    @Autowired MockMvc mvc;
    @Autowired ConfigRepository configs;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test void taskApiSavesDraftRenamesAndRejectsLegacyWrites() throws Exception {
        long source = configs.addSource(new SourceConfig(null, "api-source", DatabaseType.MYSQL,
                "127.0.0.1", 3306, "", "root", "password", 500, false, null));
        long target = configs.addTarget(new TargetConfig(null, "api-target", TargetType.MYSQL,
                Map.of("host", "127.0.0.1", "database", "demo", "username", "root", "password", "password"), false, null));
        var request = Map.of("name", "API task", "sourceId", source, "targetId", target,
                "syncMode", "INCREMENTAL_ONLY", "tables", new Object[]{Map.of(
                        "sourceDatabase", "demo", "sourceTable", "orders", "targetTable", "orders",
                        "columns", Map.of("id", "id"))});
        String created = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).path("id").asLong();

        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.tables.length()").value(1));
        mvc.perform(patch("/api/tasks/" + id + "/metadata").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed task\",\"description\":\"updated\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Renamed task"));
        mvc.perform(get("/api/tasks?targetType=MYSQL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetType").value("MYSQL"))
                .andExpect(jsonPath("$.items[0].lagMs").value(0));
        mvc.perform(get("/api/tasks?targetType=KAFKA")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        jdbc.sql("UPDATE sync_task SET status='DEGRADED' WHERE id=:id").param("id",id).update();
        mvc.perform(get("/api/tasks?view=running")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/tasks?view=abnormal&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        jdbc.sql("INSERT INTO alert_event(id,task_id,alert_type,status,title,message) VALUES(99001,:task,'TABLE_FAILURE','OPEN','test','test'),(99002,:task,'TABLE_FAILURE','OPEN','test','test')").param("task",id).update();
        mvc.perform(get("/api/tasks?view=open-alerts&size=1")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/tasks/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.openAlerts").value(2));
        jdbc.sql("UPDATE alert_event SET status='RESOLVED' WHERE task_id=:id").param("id",id).update();
        mvc.perform(get("/api/tasks?view=open-alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/monitor?range=5m")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Renamed task"));
        mvc.perform(get("/api/tasks/" + id + "/logs?page=0&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/tasks/" + id + "/metrics?range=1h&page=0&size=20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/mappings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error", containsString("/api/tasks")));
    }
}
