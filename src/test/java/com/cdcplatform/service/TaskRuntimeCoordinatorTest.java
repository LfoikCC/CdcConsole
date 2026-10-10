package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:debezium-properties-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-debezium-cache",
        "cdc.debezium.directory=target/test-debezium-offsets"
})
class TaskRuntimeCoordinatorTest {
    @Autowired TaskRuntimeCoordinator coordinator;
    @Autowired TaskRepository tasks;
    @Autowired ConfigRepository configs;

    @Test void mysqlFullModeUsesDebeziumSnapshotAndIsolatedOffsets() {
        long sourceId = configs.addSource(new SourceConfig(null, "mysql-debezium", DatabaseType.MYSQL,
                "127.0.0.1", 3306, "", "root", "password", 500, false, null));
        long targetId = configs.addTarget(new TargetConfig(null, "target-debezium", TargetType.MYSQL,
                Map.of("host", "127.0.0.1", "database", "target_db", "username", "root", "password", "password"), false, null));
        long id = tasks.create(new SyncTask(null, "debezium-test", "", sourceId, targetId,
                SyncMode.FULL_INCREMENTAL, null, "Asia/Shanghai", null, null, null, null,
                List.of(new SyncTaskTable(null, null, "test_db", null, "orders", null, "orders", Map.of("id", "id"),
                        List.of("id"), false, false, null, null, null, Map.of(), null, null, null, null,
                        0, 0, null, null, null)), null, null));
        var task = tasks.task(id);
        var properties = coordinator.properties(task, task.tables().getFirst(), configs.source(sourceId, true));

        assertThat(properties.getProperty("snapshot.mode")).isEqualTo("initial");
        assertThat(properties.getProperty("connector.class")).isEqualTo("io.debezium.connector.mysql.MySqlConnector");
        assertThat(properties.getProperty("offset.storage.file.filename")).contains("offset-" + task.tables().getFirst().mappingId());
        assertThat(properties.getProperty("table.include.list")).contains("test_db.orders");
        assertThat(properties.getProperty("snapshot.fetch.size")).isEqualTo("10000");
        assertThat(properties.getProperty("max.batch.size")).isEqualTo("500");
        assertThat(properties.getProperty("decimal.handling.mode")).isEqualTo("string");
        assertThat(Long.parseLong(properties.getProperty("database.server.id"))).isPositive();
    }

    @Test void sqlServerFilterIncludesDatabaseAndSchema() {
        long sourceId = configs.addSource(new SourceConfig(null, "sqlserver-debezium", DatabaseType.SQLSERVER,
                "127.0.0.1", 1433, "", "sa", "password", 500, false, null));
        long targetId = configs.addTarget(new TargetConfig(null, "sqlserver-target", TargetType.SQLSERVER,
                Map.of("host", "127.0.0.1", "database", "target_db", "username", "sa", "password", "password"), false, null));
        long id = tasks.create(new SyncTask(null, "sqlserver-test", "", sourceId, targetId,
                SyncMode.INCREMENTAL_ONLY, null, "Asia/Shanghai", null, null, null, null,
                List.of(new SyncTaskTable(null, null, "source_db", "sales", "orders", "dbo", "orders", Map.of("id", "id"),
                        List.of("id"), false, false, null, null, null, Map.of(), null, null, null, null,
                        0, 0, null, null, null)), null, null));
        var task = tasks.task(id);
        var properties = coordinator.properties(task, task.tables().getFirst(), configs.source(sourceId, true));

        assertThat(properties.getProperty("snapshot.mode")).isEqualTo("no_data");
        assertThat(properties.getProperty("table.include.list")).contains("source_db.sales.orders");
    }

    @Test void convertsPre2001DatetimeEpochMillisBeforePersistingBatch() throws Exception {
        Method row = TaskRuntimeCoordinator.class.getDeclaredMethod("row", com.fasterxml.jackson.databind.JsonNode.class);
        row.setAccessible(true);
        var values = (Map<String, Object>) row.invoke(coordinator, new ObjectMapper()
                .readTree("{\"updated_at\":955641803000,\"id\":12}"));

        assertThat(values.get("updated_at")).isEqualTo(LocalDateTime.of(2000, 4, 13, 16, 3, 23));
        assertThat(values.get("id")).isEqualTo(12);
    }
}
