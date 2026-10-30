package com.cdcplatform.service.dev;

import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevOperatorSqlTest {

    private final DevOperatorSql operators = new DevOperatorSql();

    private static final List<ColumnMeta> UPSTREAM = List.of(
            new ColumnMeta(1, "id", "BIGINT"),
            new ColumnMeta(2, "amount", "DECIMAL(38,10)"),
            new ColumnMeta(3, "status", "VARCHAR"));

    private static final String UP = "\"DEV_TMP\".\"T_1_n1_abc\"";

    @Test
    void projectSelectsPhysicalColumnsAndKeepsLabels() {
        var plan = operators.plan("PROJECT", Map.of("items", List.of(
                Map.of("column", "id"),
                Map.of("column", "amount", "alias", "金额"))), UP, UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("id", "金额");
        // 拼进 SQL 的必须是编号，不能是用户填的列名
        assertThat(plan.selectSql()).contains("up.\"C1\"").contains("up.\"C2\"");
        assertThat(plan.selectSql()).doesNotContain("\"amount\"");
        assertThat(plan.selectSql()).contains("FROM " + UP + " up");
    }

    @Test
    void projectWithoutItemsPassesEverythingThrough() {
        var plan = operators.plan("PROJECT", Map.of(), UP, UPSTREAM);

        assertThat(plan.columns()).hasSize(3);
        assertThat(plan.selectSql()).contains("up.\"C1\"").contains("up.\"C3\"");
    }

    @Test
    void projectSupportsCastWithWhitelist() {
        var plan = operators.plan("PROJECT", Map.of("items", List.of(
                Map.of("column", "amount", "type", "INTEGER"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("CAST(up.\"C2\" AS INTEGER)");
        assertThat(plan.columns().get(0).h2Type()).isEqualTo("INTEGER");
    }

    @Test
    void projectRejectsUnknownCast() {
        assertThatThrownBy(() -> operators.plan("PROJECT", Map.of("items", List.of(
                Map.of("column", "amount", "type", "DROP TABLE"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的转换类型");
    }

    @Test
    void filterBindsValuesAsParameters() {
        var plan = operators.plan("FILTER", Map.of("conditions", List.of(
                Map.of("column", "amount", "op", ">", "value", "100"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("WHERE up.\"C2\" > ?");
        assertThat(plan.params()).containsExactly("100");
        // 值不能拼进 SQL
        assertThat(plan.selectSql()).doesNotContain("100");
    }

    @Test
    void filterSupportsIsNullWithoutValue() {
        var plan = operators.plan("FILTER", Map.of("conditions", List.of(
                Map.of("column", "status", "op", "IS NULL"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("up.\"C3\" IS NULL");
        assertThat(plan.params()).isEmpty();
    }

    @Test
    void filterExpandsInAndBetween() {
        var in = operators.plan("FILTER", Map.of("conditions", List.of(
                Map.of("column", "status", "op", "IN", "value", "paid,new"))), UP, UPSTREAM);
        assertThat(in.selectSql()).contains("up.\"C3\" IN (?, ?)");
        assertThat(in.params()).containsExactly("paid", "new");

        var between = operators.plan("FILTER", Map.of("conditions", List.of(
                Map.of("column", "amount", "op", "BETWEEN", "value", "10,20"))), UP, UPSTREAM);
        assertThat(between.selectSql()).contains("up.\"C2\" BETWEEN ? AND ?");
        assertThat(between.params()).containsExactly("10", "20");
    }

    @Test
    void filterJoinsConditionsWithConfiguredOperator() {
        var plan = operators.plan("FILTER", Map.of("join", "OR", "conditions", List.of(
                Map.of("column", "amount", "op", ">", "value", "100"),
                Map.of("column", "status", "op", "=", "value", "paid"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains(" OR ");
        assertThat(plan.params()).containsExactly("100", "paid");
    }

    @Test
    void filterRejectsUnknownOperator() {
        assertThatThrownBy(() -> operators.plan("FILTER", Map.of("conditions", List.of(
                Map.of("column", "amount", "op", "DROP", "value", "1"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的比较操作符");
    }

    @Test
    void sortWritesOrderIntoOrdColumn() {
        var plan = operators.plan("SORT", Map.of("keys", List.of(
                Map.of("column", "amount", "direction", "DESC"),
                Map.of("column", "id", "direction", "ASC"))), UP, UPSTREAM);

        assertThat(plan.selectSql())
                .contains("ROW_NUMBER() OVER (ORDER BY up.\"C2\" DESC, up.\"C1\" ASC)");
        // 排序语义固化到 ORD，下游才读得回这个顺序
        assertThat(plan.selectSql()).startsWith("SELECT ROW_NUMBER()");
    }

    @Test
    void sortWithoutKeysFallsBackToUpstreamOrder() {
        var plan = operators.plan("SORT", Map.of(), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("ROW_NUMBER() OVER (ORDER BY up.\"ORD\")");
    }

    @Test
    void dedupPartitionsOnConfiguredKeys() {
        var plan = operators.plan("DEDUP", Map.of("keys", List.of("status")), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("PARTITION BY \"C3\" ORDER BY \"ORD\"");
        assertThat(plan.selectSql()).contains("WHERE x.\"RN\" = 1");
        assertThat(plan.columns()).hasSize(3);
    }

    @Test
    void dedupWithoutKeysPartitionsOnEveryColumn() {
        var plan = operators.plan("DEDUP", Map.of(), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("PARTITION BY \"C1\", \"C2\", \"C3\"");
    }

    @Test
    void unknownColumnIsReportedClearly() {
        assertThatThrownBy(() -> operators.plan("FILTER", Map.of("conditions", List.of(
                Map.of("column", "not_there", "op", "=", "value", "1"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上游没有列 not_there");
    }

    @Test
    void unknownOperatorIsRejected() {
        assertThatThrownBy(() -> operators.plan("JOIN", Map.of(), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的算子");
    }

    // ------------------------------------------------------------ AGGREGATE

    @Test
    void aggregateBuildsGroupByAndAggregates() {
        var plan = operators.plan("AGGREGATE", Map.of(
                "groupBy", List.of("status"),
                "aggregates", List.of(
                        Map.of("function", "SUM", "column", "amount", "alias", "总金额"),
                        Map.of("function", "COUNT", "column", "", "alias", "订单数"))), UP, UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("status", "总金额", "订单数");
        assertThat(plan.selectSql()).contains("GROUP BY up.\"C3\"");
        assertThat(plan.selectSql()).contains("SUM(up.\"C2\")");
        assertThat(plan.selectSql()).contains("COUNT(*)");
        // ORD 取组内最早的上游行，顺序可复现
        assertThat(plan.selectSql()).startsWith("SELECT ROW_NUMBER() OVER (ORDER BY MIN(up.\"ORD\"))");
    }

    @Test
    void aggregateGlobalWithoutGroupByOmitsClause() {
        var plan = operators.plan("AGGREGATE", Map.of(
                "aggregates", List.of(Map.of("function", "COUNT", "column", "", "alias", "总行数"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).doesNotContain("GROUP BY");
        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("总行数");
    }

    @Test
    void aggregateTypesFollowFunction() {
        var plan = operators.plan("AGGREGATE", Map.of(
                "groupBy", List.of("status"),
                "aggregates", List.of(
                        Map.of("function", "COUNT", "column", "", "alias", "n"),
                        Map.of("function", "SUM", "column", "amount", "alias", "s"),
                        Map.of("function", "MIN", "column", "status", "alias", "lo"),
                        Map.of("function", "AVG", "column", "id", "alias", "a"))), UP, UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::h2Type)
                .containsExactly("VARCHAR", "BIGINT", "DECIMAL(38,10)", "VARCHAR", "DECIMAL(38,10)");
    }

    @Test
    void aggregateRejectsUnknownFunction() {
        assertThatThrownBy(() -> operators.plan("AGGREGATE", Map.of(
                "aggregates", List.of(Map.of("function", "MEDIAN", "column", "amount", "alias", "m"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的聚合函数");
    }

    @Test
    void aggregateRejectsSumOnText() {
        assertThatThrownBy(() -> operators.plan("AGGREGATE", Map.of(
                "aggregates", List.of(Map.of("function", "SUM", "column", "status", "alias", "s"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要数值列");
    }

    @Test
    void aggregateRejectsDuplicateAlias() {
        assertThatThrownBy(() -> operators.plan("AGGREGATE", Map.of(
                "aggregates", List.of(
                        Map.of("function", "SUM", "column", "amount", "alias", "合计"),
                        Map.of("function", "MAX", "column", "amount", "alias", "合计"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输出列名「合计」重复");
    }

    // ------------------------------------------------------------ CALCULATE

    @Test
    void calculateTranslatesLabelsToPhysicalColumns() {
        var plan = operators.plan("CALCULATE", Map.of(
                "alias", "含税金额", "expression", "ROUND(amount * 1.13, 2)", "type", "DECIMAL(38,10)"),
                UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("CAST(ROUND(up.\"C2\" * 1.13, 2) AS DECIMAL(38,10))");
        assertThat(plan.selectSql()).doesNotContain("amount");
    }

    @Test
    void calculateKeepsUpstreamColumnsInOrder() {
        var plan = operators.plan("CALCULATE", Map.of(
                "alias", "含税金额", "expression", "amount * 1.13", "type", "DECIMAL(38,10)"), UP, UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label)
                .containsExactly("id", "amount", "status", "含税金额");
        assertThat(plan.columns().get(3).h2Type()).isEqualTo("DECIMAL(38,10)");
        assertThat(plan.columns().get(3).index()).isEqualTo(4);
    }

    @Test
    void calculateRejectsUnknownColumn() {
        assertThatThrownBy(() -> operators.plan("CALCULATE", Map.of(
                "alias", "x", "expression", "not_there + 1", "type", "DOUBLE"), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not_there");
    }

    @Test
    void calculateRejectsAliasCollidingWithUpstream() {
        assertThatThrownBy(() -> operators.plan("CALCULATE", Map.of(
                "alias", "amount", "expression", "amount * 2", "type", "DOUBLE"), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("与上游列重名");
    }

    @Test
    void calculateRejectsUnknownType() {
        assertThatThrownBy(() -> operators.plan("CALCULATE", Map.of(
                "alias", "x", "expression", "amount", "type", "MONEY"), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的计算结果类型");
    }

    // -------------------------------------------------------- REPLACE_VALUE

    @Test
    void replaceValueBindsValuesAsParameters() {
        var plan = operators.plan("REPLACE_VALUE", Map.of("rules", List.of(
                Map.of("column", "status", "from", "paid", "to", "已支付"),
                Map.of("column", "status", "from", "unpaid", "to", "未支付"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("CASE WHEN up.\"C3\" = ? THEN ?");
        assertThat(plan.selectSql()).contains("ELSE up.\"C3\" END");
        assertThat(plan.selectSql()).doesNotContain("已支付");
        assertThat(plan.params()).containsExactly("paid", "已支付", "unpaid", "未支付");
        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("id", "amount", "status");
    }

    @Test
    void replaceValueRejectsNullFrom() {
        assertThatThrownBy(() -> operators.plan("REPLACE_VALUE", Map.of("rules", List.of(
                Map.of("column", "status", "to", "x"))), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Null 值替换");
    }

    // --------------------------------------------------------- REPLACE_NULL

    @Test
    void replaceNullUsesCoalesce() {
        var plan = operators.plan("REPLACE_NULL", Map.of("fills", List.of(
                Map.of("column", "status", "value", "未知"),
                Map.of("column", "amount", "value", "0"))), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains("COALESCE(up.\"C3\", ?)");
        assertThat(plan.selectSql()).contains("COALESCE(up.\"C2\", ?)");
        // 参数按上游列顺序绑定：amount 在 status 之前，与规则书写顺序无关
        assertThat(plan.params()).containsExactly("0", "未知");
        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("id", "amount", "status");
    }

    // -------------------------------------------------------- MERGE_COLUMNS

    @Test
    void mergeColumnsFoldsConcatWithSeparator() {
        var plan = operators.plan("MERGE_COLUMNS", Map.of(
                "columns", List.of("id", "status"), "separator", "-", "alias", "标识"), UP, UPSTREAM);

        assertThat(plan.selectSql()).contains(
                "CONCAT(CONCAT(COALESCE(CAST(up.\"C1\" AS VARCHAR), ''), ?), "
                        + "COALESCE(CAST(up.\"C3\" AS VARCHAR), ''))");
        assertThat(plan.params()).containsExactly("-");
        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("id", "amount", "status", "标识");
        assertThat(plan.columns().get(3).h2Type()).isEqualTo("VARCHAR");
    }

    @Test
    void mergeColumnsDropsSourcesAndRenumbers() {
        var plan = operators.plan("MERGE_COLUMNS", Map.of(
                "columns", List.of("id", "status"), "separator", "-", "alias", "标识", "dropSources", true),
                UP, UPSTREAM);

        assertThat(plan.columns()).extracting(ColumnMeta::label).containsExactly("amount", "标识");
        assertThat(plan.columns()).extracting(ColumnMeta::index).containsExactly(1, 2);
    }

    @Test
    void mergeColumnsRequiresTwoColumns() {
        assertThatThrownBy(() -> operators.plan("MERGE_COLUMNS", Map.of(
                "columns", List.of("id"), "alias", "x"), UP, UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要两个字段");
    }
}
