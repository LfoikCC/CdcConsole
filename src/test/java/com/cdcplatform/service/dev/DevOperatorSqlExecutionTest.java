package com.cdcplatform.service.dev;

import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 五个算子的 SQL 在真实 H2 上跑一遍。
 *
 * <p>单测能验证拼出来的字符串，却抓不到两类问题：{@code ORD} 列的契约（每个 SELECT 必须原样带出，
 * 否则下游按 ORD 排序会失败）和类型错配（{@code SUM(BIGINT)} 返回 NUMERIC）。这两类只有真的执行才会暴露。
 */
class DevOperatorSqlExecutionTest {

    private static Connection connection;
    private final DevOperatorSql operators = new DevOperatorSql();

    private static final List<ColumnMeta> UPSTREAM = List.of(
            new ColumnMeta(1, "id", "BIGINT"),
            new ColumnMeta(2, "amount", "DECIMAL(38,10)"),
            new ColumnMeta(3, "status", "VARCHAR")
    );

    @BeforeAll
    static void createFixture() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:dev_operator;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE T0 ("C1" BIGINT, "C2" DECIMAL(38,10), "C3" VARCHAR, "ORD" BIGINT)
                    """);
            statement.execute("""
                    INSERT INTO T0 VALUES
                      (1, 100.5, 'paid', 1),
                      (2, 200, 'unpaid', 2),
                      (3, 300, 'paid', 3),
                      (4, NULL, NULL, 4)
                    """);
        }
    }

    @AfterAll
    static void closeFixture() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    /** 执行算子 SQL，返回去掉 ORD 之后的行。 */
    private List<List<Object>> execute(DevOperatorSql.Plan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(plan.selectSql())) {
            for (int i = 0; i < plan.params().size(); i++) {
                statement.setObject(i + 1, plan.params().get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                int columns = resultSet.getMetaData().getColumnCount();
                List<List<Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    List<Object> row = new ArrayList<>();
                    // 第 1 列固定是 ORD，业务列从第 2 列开始
                    for (int column = 2; column <= columns; column++) {
                        row.add(resultSet.getObject(column));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    @Test
    void aggregateGroupsAndSums() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("AGGREGATE", Map.of(
                "groupBy", List.of("status"),
                "aggregates", List.of(Map.of("function", "SUM", "column", "amount", "alias", "total"))
        ), "T0", UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("status", "total");
        List<List<Object>> rows = execute(plan);
        assertThat(rows).hasSize(3);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            totals.put(String.valueOf(row.get(0)), (BigDecimal) row.get(1));
        }
        assertThat(totals.get("paid")).isEqualByComparingTo("400.5");
        assertThat(totals.get("unpaid")).isEqualByComparingTo("200");
        // 全 NULL 分组也保留，SUM 为 NULL
        assertThat(totals).containsKey("null");
    }

    @Test
    void aggregateWithoutGroupByProducesSingleRow() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("AGGREGATE", Map.of(
                "aggregates", List.of(
                        Map.of("function", "COUNT", "alias", "rows"),
                        Map.of("function", "COUNT", "column", "status", "alias", "with_status"))
        ), "T0", UPSTREAM);

        List<List<Object>> rows = execute(plan);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get(0)).longValue()).isEqualTo(4L);
        assertThat(((Number) rows.get(0).get(1)).longValue()).isEqualTo(3L);
    }

    @Test
    void calculateCastsExpression() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("CALCULATE", Map.of(
                "alias", "含税金额", "expression", "amount * 1.13", "type", "DECIMAL(38,10)"
        ), "T0", UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label)
                .containsExactly("id", "amount", "status", "含税金额");
        List<List<Object>> rows = execute(plan);
        assertThat(rows).hasSize(4);
        assertThat((BigDecimal) rows.get(0).get(3)).isEqualByComparingTo("113.565");
        assertThat(rows.get(3).get(3)).isNull();
    }

    @Test
    void replaceValueRewritesMatchedRows() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("REPLACE_VALUE", Map.of("rules", List.of(
                Map.of("column", "status", "from", "paid", "to", "已支付"),
                Map.of("column", "status", "from", "unpaid", "to", "未支付")
        )), "T0", UPSTREAM);

        List<List<Object>> rows = execute(plan);
        assertThat(rows).extracting(row -> row.get(2))
                .containsExactly("已支付", "未支付", "已支付", null);
    }

    @Test
    void replaceNullFillsOnlyNullCells() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("REPLACE_NULL", Map.of("fills", List.of(
                Map.of("column", "status", "value", "未知"),
                Map.of("column", "amount", "value", "0")
        )), "T0", UPSTREAM);

        List<List<Object>> rows = execute(plan);
        assertThat(rows).hasSize(4);
        // COALESCE 会把字符串参数按列类型转换，金额填 "0" 回来是数值 0 而不是字符串
        assertThat(new BigDecimal(String.valueOf(rows.get(3).get(1)))).isEqualByComparingTo("0");
        assertThat(rows.get(3).get(2)).isEqualTo("未知");
        assertThat(rows.get(0).get(2)).isEqualTo("paid");
    }

    @Test
    void mergeColumnsJoinsWithSeparatorAndSkipsNulls() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("MERGE_COLUMNS", Map.of(
                "columns", List.of("id", "status"), "separator", "-", "alias", "标识"
        ), "T0", UPSTREAM);

        List<List<Object>> rows = execute(plan);
        assertThat(rows).extracting(row -> row.get(3))
                .containsExactly("1-paid", "2-unpaid", "3-paid", "4-");
    }

    @Test
    void mergeColumnsCanDropSources() throws Exception {
        DevOperatorSql.Plan plan = operators.plan("MERGE_COLUMNS", Map.of(
                "columns", List.of("id", "status"), "separator", "-", "alias", "标识", "dropSources", true
        ), "T0", UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("amount", "标识");
        assertThat(execute(plan).get(0)).hasSize(2);
    }
}
