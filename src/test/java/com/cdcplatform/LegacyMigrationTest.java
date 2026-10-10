package com.cdcplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMigrationTest {
    @Test void baselinesExistingSchemaAndMigratesMappingWithoutReusingCheckpoint() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:legacy-migration;DB_CLOSE_DELAY=-1", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO cdc_source(id,name,db_type,host,port,database_name,username,password,poll_interval_ms,enabled) " +
                        "VALUES(10,'legacy source','MYSQL','127.0.0.1',3306,'','root','password',500,TRUE)");
                statement.executeUpdate("INSERT INTO cdc_target(id,name,target_type,config_json,enabled) " +
                        "VALUES(11,'legacy target','MYSQL','{}',TRUE)");
                statement.executeUpdate("INSERT INTO table_mapping(id,source_id,target_id,source_database,source_table,target_table,columns_json," +
                        "include_operation,include_captured_at,operation_values_json,enabled) " +
                        "VALUES(12,10,11,'test_db','orders','orders','{\"id\":\"id\"}',FALSE,FALSE,'{}',TRUE)");
                statement.executeUpdate("INSERT INTO cdc_checkpoint(mapping_id,last_lsn) VALUES(12,'BINLOG:mysql-bin.000001:200')");
            }
            Flyway.configure().dataSource(new SingleConnectionDataSource(connection, true))
                    .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").load().migrate();
            try (var statement = connection.createStatement()) {
                try (var rs = statement.executeQuery("SELECT status,sync_mode FROM sync_task WHERE id=12")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("STOPPED");
                    assertThat(rs.getString("sync_mode")).isEqualTo("INCREMENTAL_ONLY");
                }
                try (var rs = statement.executeQuery("SELECT mapping_id,source_database FROM sync_task_table WHERE task_id=12")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("mapping_id")).isEqualTo(12);
                    assertThat(rs.getString("source_database")).isEqualTo("test_db");
                }
                try (var rs = statement.executeQuery("SELECT enabled FROM table_mapping WHERE id=12")) {
                    assertThat(rs.next()).isTrue(); assertThat(rs.getBoolean(1)).isFalse();
                }
                try (var rs = statement.executeQuery("SELECT COUNT(*) FROM sync_checkpoint")) {
                    rs.next(); assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }
}
