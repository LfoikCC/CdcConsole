package com.cdcplatform.service.dev;

import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.TargetType;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 目标表不存在时按上游结构建表：只校验 SQL 生成，不连库。 */
class DevTargetWriterTest {

    private static TargetConfig target(TargetType type) {
        return new TargetConfig(1L, "out", type,
                Map.of("database", "ADS", "username", "u", "password", "p"), true, null);
    }

    private static final List<ColumnMeta> COLUMNS = List.of(
            new ColumnMeta(1, "订单号", "BIGINT"),
            new ColumnMeta(2, "客户", "VARCHAR"),
            new ColumnMeta(3, "金额", "DECIMAL(38,10)"),
            new ColumnMeta(4, "创建时间", "TIMESTAMP"));

    private static final List<String> TARGETS = List.of("order_id", "customer", "amount", "created_at");

    @Test
    void createsAMysqlTableWithTheUpstreamTypes() {
        String ddl = DevTargetWriter.createTableSql(target(TargetType.MYSQL), "orders", TARGETS, COLUMNS);

        assertThat(ddl).isEqualTo("CREATE TABLE IF NOT EXISTS `orders` ("
                + "`order_id` BIGINT, `customer` VARCHAR(1024), `amount` DECIMAL(38,10), `created_at` DATETIME)");
    }

    @Test
    void createsAStarRocksTableWithBuckets() {
        String ddl = DevTargetWriter.createTableSql(target(TargetType.STARROCKS_STREAM_LOAD), "orders", TARGETS, COLUMNS);

        // StarRocks 必须有分桶；副本数给 1，BE 少的小集群也能建
        assertThat(ddl).contains("ENGINE=OLAP DUPLICATE KEY(`order_id`)")
                .contains("DISTRIBUTED BY HASH(`order_id`) BUCKETS 1")
                .contains("replication_num");
    }

    @Test
    void guardsTheSqlServerCreateWithObjectId() {
        String ddl = DevTargetWriter.createTableSql(target(TargetType.SQLSERVER), "orders", TARGETS, COLUMNS);

        // SQL Server 没有 IF NOT EXISTS，用 OBJECT_ID 兜底，重复执行不会报错
        assertThat(ddl).startsWith("IF OBJECT_ID(N'orders', N'U') IS NULL CREATE TABLE [orders]")
                .contains("[customer] NVARCHAR(1024)")
                .contains("[created_at] DATETIME2");
    }

    @Test
    void translatesUpstreamTypesPerDialect() {
        assertThat(DevTargetWriter.columnType(TargetType.MYSQL, "BOOLEAN")).isEqualTo("TINYINT(1)");
        assertThat(DevTargetWriter.columnType(TargetType.SQLSERVER, "BOOLEAN")).isEqualTo("BIT");
        assertThat(DevTargetWriter.columnType(TargetType.STARROCKS_STREAM_LOAD, "CLOB")).isEqualTo("STRING");
        assertThat(DevTargetWriter.columnType(TargetType.MYSQL, "CLOB")).isEqualTo("TEXT");
        assertThat(DevTargetWriter.columnType(TargetType.MYSQL, "DOUBLE")).isEqualTo("DOUBLE");
        assertThat(DevTargetWriter.columnType(TargetType.SQLSERVER, "DOUBLE")).isEqualTo("FLOAT");
    }
}
