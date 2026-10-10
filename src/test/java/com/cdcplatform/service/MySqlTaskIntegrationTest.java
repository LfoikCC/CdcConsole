package com.cdcplatform.service;

import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.SyncTask;
import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:mysql-task-integration;DB_CLOSE_DELAY=-1")
class MySqlTaskIntegrationTest {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String DATABASE = "cdc_integration";

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName(DATABASE).withUsername("cdc_test").withPassword(PASSWORD)
            .withCommand("--server-id=22334", "--log-bin=mysql-bin", "--binlog-format=ROW", "--binlog-row-image=FULL");

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        String directory = "target/test-mysql-task-" + UUID.randomUUID();
        registry.add("cdc.cache.directory", () -> directory + "/cache");
        registry.add("cdc.debezium.directory", () -> directory + "/offsets");
    }

    @Autowired ConfigRepository configs;
    @Autowired TaskRepository tasks;
    @Autowired TaskConfigurationService configuration;

    @Test
    void fullSnapshotThenBinlogChangesAndPauseResume() throws Exception {
        sql("CREATE TABLE orders (id BIGINT PRIMARY KEY, note VARCHAR(100) NOT NULL)");
        sql("CREATE TABLE orders_copy (id BIGINT PRIMARY KEY, note VARCHAR(100) NOT NULL, captured_at DATETIME(3))");
        sql("INSERT INTO orders VALUES (1, 'initial')");
        sql("INSERT INTO orders_copy VALUES (90, 'target-only', NULL)");

        long sourceId = configs.addSource(new SourceConfig(null, "integration-source", DatabaseType.MYSQL,
                mysql.getHost(), mysql.getMappedPort(3306), "", "root", PASSWORD, 500, true, null));
        long targetId = configs.addTarget(new TargetConfig(null, "integration-target", TargetType.MYSQL,
                Map.of("host", mysql.getHost(), "port", mysql.getMappedPort(3306), "database", DATABASE,
                        "username", "root", "password", PASSWORD), true, null));
        long taskId = tasks.create(new SyncTask(null, "mysql-to-mysql", "", sourceId, targetId,
                SyncMode.FULL_INCREMENTAL, null, "Asia/Shanghai", null, null, null, null,
                List.of(new SyncTaskTable(null, null, DATABASE, null, "orders", null, "orders_copy",
                        Map.of("id", "id", "note", "note"), List.of("id"), false, true,
                        null, "captured_at", "TIMESTAMP", Map.of(), null, null, null, null,
                        0, 0, null, null, null)), null, null));

        try {
            configuration.start(taskId, false);
            for (int id = 100; id < 120; id++) sql("INSERT INTO orders VALUES (" + id + ", 'concurrent')");
            await(() -> value(1).equals("initial"), "snapshot row");
            for (int id = 100; id < 120; id++) {
                int key = id;
                await(() -> value(key).equals("concurrent"), "row written around snapshot " + key);
            }
            assertThat(value(90)).isEqualTo("target-only");
            assertThat(capturedAt(1)).isBetween(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(2),
                    LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(2));

            sql("INSERT INTO orders VALUES (2, 'inserted')");
            await(() -> value(2).equals("inserted"), "insert event");
            sql("UPDATE orders SET note='updated' WHERE id=1");
            await(() -> value(1).equals("updated"), "update event");
            sql("DELETE FROM orders WHERE id=2");
            await(() -> value(2).isEmpty(), "delete event");

            configuration.pause(taskId);
            sql("UPDATE orders SET note='during-pause' WHERE id=1");
            Thread.sleep(1200);
            assertThat(value(1)).isEqualTo("updated");
            configuration.start(taskId, true);
            await(() -> value(1).equals("during-pause"), "resumed binlog event");
            assertThat(value(90)).isEqualTo("target-only");
            assertThat(tasks.task(taskId).tables().getFirst().errorCount()).isZero();
        } finally {
            configuration.stop(taskId);
        }
    }

    private void sql(String statement) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", PASSWORD);
             var query = connection.createStatement()) { query.execute(statement); }
    }

    private String value(int id) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", PASSWORD);
             var query = connection.prepareStatement("SELECT note FROM orders_copy WHERE id=?")) {
            query.setInt(1, id);
            try (var results = query.executeQuery()) { return results.next() ? results.getString(1) : ""; }
        }
    }

    private LocalDateTime capturedAt(int id) throws Exception {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", PASSWORD);
             var query = connection.prepareStatement("SELECT captured_at FROM orders_copy WHERE id=?")) {
            query.setInt(1, id);
            try (var results = query.executeQuery()) { results.next(); return results.getTimestamp(1).toLocalDateTime(); }
        }
    }

    private void await(CheckedCondition condition, String description) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        while (Instant.now().isBefore(deadline)) {
            if (condition.test()) return;
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for " + description + ": " + tasks.tasks());
    }

    @FunctionalInterface private interface CheckedCondition { boolean test() throws Exception; }
}
