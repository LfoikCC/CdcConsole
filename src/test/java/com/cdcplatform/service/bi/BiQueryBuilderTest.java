package com.cdcplatform.service.bi;

import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.service.bi.BiQueryBuilder.CardConfig;
import com.cdcplatform.service.bi.BiQueryBuilder.Column;
import com.cdcplatform.service.bi.BiQueryBuilder.Computed;
import com.cdcplatform.service.bi.BiQueryBuilder.Dimension;
import com.cdcplatform.service.bi.BiQueryBuilder.Filter;
import com.cdcplatform.service.bi.BiQueryBuilder.Metric;
import com.cdcplatform.service.bi.BiQueryBuilder.Sort;
import com.cdcplatform.service.bi.BiQueryBuilder.Source;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BiQueryBuilderTest {

    private final BiQueryBuilder builder = new BiQueryBuilder();

    private static final List<Column> COLUMNS = List.of(
            new Column("region", "region"),
            new Column("amount", "amount"),
            new Column("status", "status"));

    /** 离线数据集：物理列是 C1..Cn，真实列名只在展示时用。 */
    private static final List<Column> STORAGE_COLUMNS = List.of(
            new Column("region", "C1"),
            new Column("amount", "C2"),
            new Column("status", "C3"));

    private static Source mysqlTable() {
        return Source.table(DatabaseType.MYSQL, "CDC-SOURCE", "Orders", COLUMNS);
    }

    @Test
    void buildsGroupedQueryForTableDataset() {
        var plan = builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", "销售额"))), 5000);

        assertThat(plan.sql()).isEqualTo(
                "SELECT `region` AS c0, SUM(`amount`) AS m0 FROM `CDC-SOURCE`.`Orders` GROUP BY `region` LIMIT 201");
        assertThat(plan.labels()).containsExactly("region", "销售额");
        assertThat(plan.params()).isEmpty();
        assertThat(plan.limit()).isEqualTo(200);
    }

    @Test
    void quotesIdentifiersForSqlServerAndKeepsOrderBy() {
        var plan = builder.build(Source.table(DatabaseType.SQLSERVER, "dbo", "orders", COLUMNS),
                new CardConfig(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null)),
                        List.of(),
                        new Sort("metric", 0, "desc"), 50), 5000);

        // SQL Server 没有 LIMIT，而且 OFFSET/FETCH 强制要求 ORDER BY
        assertThat(plan.sql()).isEqualTo("SELECT [region] AS c0, SUM([amount]) AS m0 FROM [dbo].[orders]"
                + " GROUP BY [region] ORDER BY m0 DESC OFFSET 0 ROWS FETCH NEXT 51 ROWS ONLY");
        assertThat(plan.labels()).containsExactly("region", "求和(amount)");
    }

    @Test
    void alwaysAddsOrderByForSqlServerEvenWithoutSort() {
        var plan = builder.build(Source.table(DatabaseType.SQLSERVER, "dbo", "orders", COLUMNS),
                CardConfig.of(List.of(), List.of(new Metric("amount", "MAX", null))), 5000);

        assertThat(plan.sql()).endsWith(" ORDER BY m0 OFFSET 0 ROWS FETCH NEXT 201 ROWS ONLY");
    }

    @Test
    void wrapsCustomSqlAsSubQuery() {
        var plan = builder.build(Source.sql(DatabaseType.MYSQL, "CDC-SOURCE",
                        "SELECT * FROM CDC-SOURCE.Orders", COLUMNS),
                CardConfig.of(List.of(), List.of(new Metric("", "COUNT", null))), 5000);

        assertThat(plan.sql()).isEqualTo("SELECT COUNT(*) AS m0 FROM "
                + "(SELECT * FROM `CDC-SOURCE`.Orders) src LIMIT 201");
        assertThat(plan.labels()).containsExactly("行数");
    }

    @Test
    void rejectsUnsafeOrMultiStatementSql() {
        var source = Source.sql(DatabaseType.MYSQL, "CDC-SOURCE", "SELECT 1; DROP TABLE t", COLUMNS);
        assertThatThrownBy(() -> builder.build(source,
                CardConfig.of(List.of(), List.of(new Metric("", "COUNT", null))), 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单条");
    }

    @Test
    void mapsPhysicalColumnsForOfflineDatasets() {
        var plan = builder.build(Source.storage("DATASET_STORE", "DS_12_0", STORAGE_COLUMNS),
                CardConfig.of(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "AVG", "均值"))), 5000);

        // 物理列是 C1/C2，写 "region" 会报 Column not found
        assertThat(plan.sql()).isEqualTo("SELECT \"C1\" AS c0, AVG(\"C2\") AS m0"
                + " FROM \"DATASET_STORE\".\"DS_12_0\" GROUP BY \"C1\" LIMIT 201");
        assertThat(plan.labels()).containsExactly("region", "均值");
    }

    @Test
    void countsDistinctValues() {
        var plan = builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("region")),
                        List.of(new Metric("status", "COUNT_DISTINCT", null))), 5000);

        assertThat(plan.sql()).contains("COUNT(DISTINCT `status`) AS m0");
    }

    @Test
    void usesTheDimensionAliasAsItsLabel() {
        var plan = builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("region", "区域")),
                        List.of(new Metric("amount", "SUM", null))), 5000);

        // 别名只影响展示名，SQL 里还是按真实列分组
        assertThat(plan.sql()).isEqualTo(
                "SELECT `region` AS c0, SUM(`amount`) AS m0 FROM `CDC-SOURCE`.`Orders` GROUP BY `region` LIMIT 201");
        assertThat(plan.labels()).containsExactly("区域", "求和(amount)");
    }

    @Test
    void keepsRawValuesWhenAggregationIsNone() {
        var plan = builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("region"), new Dimension("status", "状态")),
                        List.of(new Metric("amount", "NONE", "金额"))), 5000);

        // 无处理 = 原样取列；有维度时它必须进 GROUP BY，否则 SQL 直接报错
        assertThat(plan.sql()).isEqualTo("SELECT `region` AS c0, `status` AS c1, `amount` AS m0"
                + " FROM `CDC-SOURCE`.`Orders` GROUP BY `region`, `status`, `amount` LIMIT 201");
        assertThat(plan.labels()).containsExactly("region", "状态", "金额");
    }

    @Test
    void doesNotAggregateOrGroupWhenNothingIsAggregated() {
        var plan = builder.build(mysqlTable(),
                CardConfig.of(List.of(), List.of(new Metric("amount", "NONE", null))), 5000);

        assertThat(plan.sql()).isEqualTo("SELECT `amount` AS m0 FROM `CDC-SOURCE`.`Orders` LIMIT 201");
        assertThat(plan.labels()).containsExactly("amount");
    }

    @Test
    void skipsGroupByWhenThereIsNoMetricOrNoDimension() {
        var dimensionOnly = builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("region")), List.of()), 5000);
        assertThat(dimensionOnly.sql()).doesNotContain("GROUP BY");

        var kpi = builder.build(mysqlTable(),
                CardConfig.of(List.of(), List.of(new Metric("amount", "SUM", "总额"))), 5000);
        assertThat(kpi.sql()).isEqualTo("SELECT SUM(`amount`) AS m0 FROM `CDC-SOURCE`.`Orders` LIMIT 201");
    }

    @Test
    void bindsFilterValuesAsParameters() {
        var plan = builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("status", "=", "已支付"),
                                new Filter("amount", ">", 100),
                                new Filter("region", "IS NOT NULL", null),
                                new Filter("region", "IN", List.of("华东", "华北"))),
                        null, 10), 5000);

        assertThat(plan.sql()).isEqualTo("SELECT `region` AS c0, SUM(`amount`) AS m0"
                + " FROM `CDC-SOURCE`.`Orders`"
                + " WHERE `status` = ? AND `amount` > ? AND `region` IS NOT NULL AND `region` IN (?, ?)"
                + " GROUP BY `region` LIMIT 11");
        assertThat(plan.params()).containsExactly("已支付", 100, "华东", "华北");
    }

    @Test
    void supportsRangeFilters() {
        var both = builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("amount", "BETWEEN", List.of(100, 500))), null, 10), 5000);
        assertThat(both.sql()).contains("WHERE `amount` BETWEEN ? AND ?");
        assertThat(both.params()).containsExactly(100, 500);

        // 只填了下限 / 只填了上限
        var fromOnly = builder.build(mysqlTable(),
                new CardConfig(List.of(), List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("amount", "BETWEEN", List.of(100))), null, 10), 5000);
        assertThat(fromOnly.sql()).contains("WHERE `amount` >= ?");

        var empty = builder.build(mysqlTable(),
                new CardConfig(List.of(), List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("amount", "BETWEEN", List.of())), null, 10), 5000);
        assertThat(empty.sql()).doesNotContain("WHERE");
    }

    @Test
    void selectsFromAListOfValues() {
        var plan = builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("status", "IN", List.of("已完成", "处理中"))), null, 10), 5000);
        assertThat(plan.sql()).contains("WHERE `status` IN (?, ?)");
        assertThat(plan.params()).containsExactly("已完成", "处理中");

        // 一个新拖进来还没勾选值的筛选不该让卡片变空
        var nothing = builder.build(mysqlTable(),
                new CardConfig(List.of(), List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("status", "IN", List.of())), null, 10), 5000);
        assertThat(nothing.sql()).doesNotContain("WHERE");
    }

    @Test
    void supportsExcludeFilters() {
        var plan = builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("status", "NOT IN", List.of("已取消", "待付款"))), null, 10), 5000);

        // 排除要保留空值行，否则 NULL NOT IN (...) 会把整行吞掉
        assertThat(plan.sql()).contains("WHERE (`status` NOT IN (?, ?) OR `status` IS NULL)");
        assertThat(plan.params()).containsExactly("已取消", "待付款");
    }

    @Test
    void countsAllRowsOfADataset() {
        var plan = builder.countRows(mysqlTable());
        assertThat(plan.sql()).isEqualTo("SELECT COUNT(*) AS v FROM `CDC-SOURCE`.`Orders`");
        assertThat(plan.labels()).containsExactly("行数");
    }

    @Test
    void sortsByTheCustomOrderOfDimensionValues() {
        var plan = builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("status")),
                        List.of(new Metric("amount", "SUM", null)), List.of(),
                        new Sort("dimension", 0, "asc", List.of("已完成", "处理中", "新建")), 10), 5000);

        assertThat(plan.sql()).endsWith("ORDER BY CASE `status` WHEN ? THEN 0 WHEN ? THEN 1 WHEN ? THEN 2 ELSE 3 END ASC LIMIT 11");
        assertThat(plan.params()).containsExactly("已完成", "处理中", "新建");
    }

    @Test
    void buildsTheDistinctValueQuery() {
        var plan = builder.distinctValues(mysqlTable(), "status", 100);
        assertThat(plan.sql()).isEqualTo("SELECT DISTINCT `status` AS v FROM `CDC-SOURCE`.`Orders`"
                + " WHERE `status` IS NOT NULL ORDER BY 1 LIMIT 101");
        assertThat(plan.limit()).isEqualTo(100);
    }

    @Test
    void ignoresFiltersThatHaveNoValueYet() {
        var plan = builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null))),
                5000);
        var withBlank = builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")),
                        List.of(new Metric("amount", "SUM", null)),
                        List.of(new Filter("status", "=", "")), null, 200), 5000);

        assertThat(withBlank.sql()).isEqualTo(plan.sql());
        assertThat(withBlank.params()).isEmpty();
    }

    @Test
    void rejectsUnknownColumnsAndOperators() {
        assertThatThrownBy(() -> builder.build(mysqlTable(),
                CardConfig.of(List.of(new Dimension("nope")), List.of()), 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有字段");

        assertThatThrownBy(() -> builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")), List.of(),
                        List.of(new Filter("region", "DROP", "x")), null, 10), 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("操作符");
    }

    @Test
    void clampsTheRowLimit() {
        assertThat(builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")), List.of(), List.of(), null, 0), 5000).limit())
                .isEqualTo(1);
        assertThat(builder.build(mysqlTable(),
                new CardConfig(List.of(new Dimension("region")), List.of(), List.of(), null, 99_999), 5000).limit())
                .isEqualTo(5000);
    }

    @Test
    void rejectsUnsafeIdentifiers() {
        var source = Source.table(DatabaseType.MYSQL, "db", "t",
                List.of(new Column("a`; DROP TABLE x", "a`; DROP TABLE x")));
        assertThatThrownBy(() -> builder.build(source,
                CardConfig.of(List.of(new Dimension("a`; DROP TABLE x")), List.of()), 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法标识符");
    }

    @Test
    void parsesCardConfigFromJsonMap() {
        var config = CardConfig.parse(Map.of(
                "dimensions", List.of(Map.of("column", "region"), Map.of("column", "status", "alias", "状态")),
                "metrics", List.of(Map.of("column", "amount", "agg", "sum", "alias", "销售额")),
                "filters", List.of(Map.of("column", "status", "op", "=", "value", "已支付")),
                "sort", Map.of("by", "metric", "index", 0, "dir", "desc"),
                "limit", 100));

        assertThat(config.dimensions()).extracting(Dimension::column).containsExactly("region", "status");
        assertThat(config.dimensions().get(1).alias()).isEqualTo("状态");
        assertThat(config.metrics()).extracting(Metric::agg).containsExactly("sum");
        assertThat(config.filters()).hasSize(1);
        assertThat(config.sort().index()).isZero();
        assertThat(config.limit()).isEqualTo(100);
    }

    @Test
    void translatesComputedFieldsByTheirDisplayName() {
        // Excel / 离线数据集的物理列是 C1..Cn：占位符要按展示名换成真实物理列
        var source = builder.withComputed(Source.storage("PUBLIC", "DS_1", STORAGE_COLUMNS),
                List.of(new Computed("客单价", "NUMBER", "SUM({amount}) / COUNT({region})")));
        var plan = builder.build(source, CardConfig.of(List.of(),
                List.of(new Metric("客单价", "NONE", null))), 5000);

        assertThat(plan.sql()).isEqualTo(
                "SELECT (SUM(\"C2\") / COUNT(\"C1\")) AS m0 FROM \"PUBLIC\".\"DS_1\" LIMIT 201");
    }

    @Test
    void rejectsNestedAggregatesAndAggregatedDimensions() {
        var source = builder.withComputed(mysqlTable(),
                List.of(new Computed("合计", "NUMBER", "SUM({amount})")));

        assertThatThrownBy(() -> builder.build(source, CardConfig.of(List.of(),
                List.of(new Metric("合计", "SUM", null))), 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("嵌套聚合");

        assertThatThrownBy(() -> builder.build(source, CardConfig.of(List.of(new Dimension("合计")),
                List.of()), 5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能当维度");
    }

    @Test
    void rejectsComputedFieldsWithoutAValidReference() {
        assertThatThrownBy(() -> builder.withComputed(mysqlTable(),
                List.of(new Computed("x", "NUMBER", "SUM({nope})"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在的字段：nope");

        // 与数据集字段重名会让引用产生歧义
        assertThatThrownBy(() -> builder.withComputed(mysqlTable(),
                List.of(new Computed("region", "TEXT", "{status}"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重名");
    }
}
