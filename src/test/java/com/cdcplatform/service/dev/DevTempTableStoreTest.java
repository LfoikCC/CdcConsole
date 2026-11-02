package com.cdcplatform.service.dev;

import com.cdcplatform.config.DevProperties;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用内存 H2 验证临时表层——这层不碰外部数据库，所以能完整跑通。
 */
class DevTempTableStoreTest {

    private JdbcDataSource dataSource;
    private DevTempTableStore store;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:devstore_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        DevProperties props = new DevProperties();
        props.setTempSchema("DEV_TMP");
        props.setBatchSize(2);
        store = new DevTempTableStore(dataSource, props);
    }

    private void seed() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // 列名带引号建，才能模拟 MySQL「列名按声明大小写返回」的行为；
            // H2 会把未加引号的标识符折成大写，那样测出来的标签就不是真实场景了。
            s.execute("CREATE TABLE SRC (\"id\" BIGINT, \"name\" VARCHAR(50), \"amount\" DECIMAL(18,2))");
            s.execute("INSERT INTO SRC VALUES (3,'c',30.5),(1,'a',10.5),(2,'b',20.5)");
        }
    }

    private static List<ColumnMeta> columnsOf(ResultSetMetaData meta) throws Exception {
        List<ColumnMeta> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(new ColumnMeta(i, meta.getColumnLabel(i),
                    DevTempTableStore.h2TypeOf(meta.getColumnType(i))));
        }
        return columns;
    }

    @Test
    void copiesResultSetAndKeepsLabels() throws Exception {
        seed();
        String table = store.tableName(42, "n1");

        long rows;
        List<ColumnMeta> columns;
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"amount\" FROM SRC")) {
            columns = columnsOf(rs.getMetaData());
            store.create(table, columns);
            rows = store.insert(table, columns.size(), rs, 1000);
        }

        assertThat(rows).isEqualTo(3);
        assertThat(store.rowCount(table)).isEqualTo(3);
        // 物理列是 C1..Cn，但读回来必须是原始列名
        assertThat(store.columnsOf(table)).extracting(ColumnMeta::label)
                .containsExactly("id", "name", "amount");
    }

    @Test
    void runSqlInfersColumnsAndWritesRows() throws Exception {
        seed();
        String table = store.tableName(42, "sql");

        var result = store.runSql(table, "SELECT \"id\", \"name\" FROM SRC WHERE \"amount\" > 15", 100);

        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.columns()).extracting(ColumnMeta::label).containsExactly("id", "name");
        var preview = store.preview(table, 10);
        assertThat(preview.columns()).containsExactly("id", "name");
        assertThat(preview.rows()).hasSize(2);
    }

    @Test
    void sqlOperatorViewMapsLogicalNamesBackToLabels() throws Exception {
        List<ColumnMeta> columns = List.of(
                new ColumnMeta(1, "id", "BIGINT"),
                new ColumnMeta(2, "name", "VARCHAR"));
        String source = store.tableName(7, "up");
        store.create(source, columns);
        store.execute("INSERT INTO " + source + " (\"ORD\",\"C1\",\"C2\") VALUES (1,1,'a'),(2,2,'b')");

        String view = store.tableName(7, "up_view");
        store.createView(view, source, columns);
        var result = store.runSql(store.tableName(7, "sql_out"),
                "SELECT id, name FROM " + view + " WHERE id > 1", 10, columns);
        store.dropView(view);

        // 视图列名是大写 ID/NAME，结果标签要还原成用户看到的 id/name
        assertThat(result.columns()).extracting(ColumnMeta::label).containsExactly("id", "name");
        assertThat(result.rows()).isEqualTo(1);
    }

    @Test
    void previewFollowsInsertionOrderThroughOrdColumn() throws Exception {
        seed();
        String table = store.tableName(42, "n2");
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"amount\" FROM SRC")) {
            var columns = columnsOf(rs.getMetaData());
            store.create(table, columns);
            store.insert(table, columns.size(), rs, 1000);
        }

        var preview = store.preview(table, 10);

        assertThat(preview.columns()).containsExactly("id", "name", "amount");
        // 源表是无序的 3,1,2；写入时按读取顺序编了 ORD，预览必须还原这个顺序
        assertThat(preview.rows()).extracting(row -> row.get(0).toString())
                .containsExactly("3", "1", "2");
        assertThat(preview.truncated()).isFalse();
    }

    @Test
    void previewReportsTruncation() throws Exception {
        seed();
        String table = store.tableName(42, "n3");
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"amount\" FROM SRC")) {
            var columns = columnsOf(rs.getMetaData());
            store.create(table, columns);
            store.insert(table, columns.size(), rs, 1000);
        }

        var preview = store.preview(table, 2);

        assertThat(preview.rows()).hasSize(2);
        assertThat(preview.truncated()).isTrue();
    }

    @Test
    void exceedingRowCapFailsLoudly() throws Exception {
        seed();
        String table = store.tableName(42, "n4");
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"amount\" FROM SRC")) {
            var columns = columnsOf(rs.getMetaData());
            store.create(table, columns);
            // 上限 2 行，源有 3 行：必须报错，不能静默截断
            assertThatThrownBy(() -> store.insert(table, columns.size(), rs, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("行上限");
        }
    }

    @Test
    void materializeRunsGeneratedSelect() throws Exception {
        seed();
        String upstream = store.tableName(42, "up");
        String downstream = store.tableName(42, "down");
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"amount\" FROM SRC")) {
            var columns = columnsOf(rs.getMetaData());
            store.create(upstream, columns);
            store.insert(upstream, columns.size(), rs, 1000);
        }

        var operators = new DevOperatorSql();
        var plan = operators.plan("FILTER", java.util.Map.of("conditions", java.util.List.of(
                java.util.Map.of("column", "amount", "op", ">", "value", "15"))),
                upstream, store.columnsOf(upstream));
        store.create(downstream, plan.columns());
        long rows = store.materialize(downstream, plan.columns(), plan.selectSql(), plan.params());

        assertThat(rows).isEqualTo(2);
        // 上游顺序是 3,1,2；过滤掉 1 之后仍是 3 在前——ORD 保住了原始顺序
        assertThat(store.preview(downstream, 10).rows()).extracting(r -> r.get(0).toString())
                .containsExactly("3", "2");
    }

    @Test
    void dropAllClearsSchemaAndCatalog() throws Exception {
        seed();
        String table = store.tableName(42, "n5");
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\" FROM SRC")) {
            var columns = columnsOf(rs.getMetaData());
            store.create(table, columns);
            store.insert(table, columns.size(), rs, 1000);
        }

        store.dropAll();

        // DEV_TMP 里不该再剩下任何 T_ 开头的中间表（目录表本身要留着）
        List<String> remaining = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='DEV_TMP'")) {
            while (rs.next()) {
                remaining.add(rs.getString(1).toUpperCase());
            }
        }
        assertThat(remaining).noneMatch(name -> name.startsWith("T_"));
        assertThatThrownBy(() -> store.rowCount(table)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tableNameIsSafeAndDistinct() {
        String a = store.tableName(7, "n-1");
        String b = store.tableName(7, "n_1");

        assertThat(a).matches("DEV_TMP\\.T_7_[A-Za-z0-9_]+");
        assertThat(a).isNotEqualTo(b);
    }
}
