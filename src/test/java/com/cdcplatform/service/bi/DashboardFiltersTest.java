package com.cdcplatform.service.bi;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看板筛选器 → 查询条件的翻译。
 *
 * <p>规则必须和前端 biFilters.js 的 filterConditions 保持一致，两边任一改动都要同步这里的断言。
 */
class DashboardFiltersTest {

    private static Map<String, Object> filter(String type, String column) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("datasetId", 7);
        map.put("column", column);
        return map;
    }

    private static Map<String, Object> condition(String column, String op, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("column", column);
        map.put("op", op);
        map.put("value", value);
        return map;
    }

    @Test
    void selectBecomesInAndExcludeBecomesNotIn() {
        Map<String, Object> select = filter("select", "status");
        select.put("values", List.of("已完成", "处理中"));
        assertThat(DashboardFilters.conditionsOf(select))
                .containsExactly(condition("status", "IN", List.of("已完成", "处理中")));

        select.put("exclude", true);
        assertThat(DashboardFilters.conditionsOf(select))
                .containsExactly(condition("status", "NOT IN", List.of("已完成", "处理中")));

        // 没勾任何值：不过滤，不能把卡片筛成空表
        select.put("values", List.of());
        assertThat(DashboardFilters.conditionsOf(select)).isEmpty();
    }

    @Test
    void singleSelectKeepsOnlyTheFirstValue() {
        Map<String, Object> single = filter("select", "status");
        single.put("multiple", false);
        single.put("values", List.of("已完成", "处理中", "作废"));
        assertThat(DashboardFilters.conditionsOf(single))
                .containsExactly(condition("status", "IN", List.of("已完成")));

        single.put("multiple", true);
        assertThat(DashboardFilters.conditionsOf(single))
                .containsExactly(condition("status", "IN", List.of("已完成", "处理中", "作废")));
    }

    @Test
    void conditionAddsWildcards() {
        Map<String, Object> base = filter("condition", "customer_name");

        base.put("op", "CONTAINS");
        base.put("keyword", "张");
        assertThat(DashboardFilters.conditionsOf(base))
                .containsExactly(condition("customer_name", "LIKE", "%张%"));

        base.put("op", "NOT_CONTAINS");
        assertThat(DashboardFilters.conditionsOf(base))
                .containsExactly(condition("customer_name", "NOT LIKE", "%张%"));

        base.put("op", "STARTS");
        assertThat(DashboardFilters.conditionsOf(base))
                .containsExactly(condition("customer_name", "LIKE", "张%"));

        base.put("op", "ENDS");
        assertThat(DashboardFilters.conditionsOf(base))
                .containsExactly(condition("customer_name", "LIKE", "%张"));

        base.put("op", "=");
        assertThat(DashboardFilters.conditionsOf(base))
                .containsExactly(condition("customer_name", "=", "张"));

        // 空关键词不参与过滤
        base.put("keyword", "  ");
        assertThat(DashboardFilters.conditionsOf(base)).isEmpty();
    }

    @Test
    void rangeKeepsNullForTheOpenEnd() {
        Map<String, Object> range = filter("range", "amount");
        range.put("valueOp", "BETWEEN");
        range.put("from", "100");
        range.put("to", "");
        assertThat(DashboardFilters.conditionsOf(range))
                .containsExactly(condition("amount", "BETWEEN", java.util.Arrays.asList("100", null)));

        range.put("valueOp", ">");
        range.put("to", null);
        assertThat(DashboardFilters.conditionsOf(range))
                .containsExactly(condition("amount", ">", "100"));

        range.put("valueOp", "IS NULL");
        assertThat(DashboardFilters.conditionsOf(range))
                .containsExactly(condition("amount", "IS NULL", null));

        range.put("valueOp", "BETWEEN");
        range.put("from", "");
        assertThat(DashboardFilters.conditionsOf(range)).isEmpty();
    }

    @Test
    void comboDropsUnfinishedRows() {
        Map<String, Object> combo = filter("combo", "");
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("column", "customer_name");
        empty.put("op", "CONTAINS");
        empty.put("value", "");
        combo.put("conditions", List.of(
                condition("status", "=", "已完成"),
                empty,
                condition("customer_name", "STARTS", "张")));

        assertThat(DashboardFilters.conditionsOf(combo)).containsExactly(
                condition("status", "=", "已完成"),
                condition("customer_name", "LIKE", "张%"));
    }

    @Test
    void appliesToRespectsDatasetAndScope() {
        Map<String, Object> select = filter("select", "status");
        select.put("values", List.of("已完成"));

        assertThat(DashboardFilters.appliesTo(select, 7L, 101L)).isTrue();
        assertThat(DashboardFilters.appliesTo(select, 8L, 101L)).isFalse();

        select.put("scope", "cards");
        select.put("cardIds", List.of(101, 102));
        assertThat(DashboardFilters.appliesTo(select, 7L, 101L)).isTrue();
        assertThat(DashboardFilters.appliesTo(select, 7L, 103L)).isFalse();
        // 还没保存的新卡没有 id，不能命中「指定卡片」
        assertThat(DashboardFilters.appliesTo(select, 7L, null)).isFalse();
    }

    @Test
    void conditionsCollectsOnlyMatchingFilters() {
        Map<String, Object> mine = filter("select", "status");
        mine.put("values", List.of("已完成"));
        Map<String, Object> otherDataset = filter("condition", "name");
        otherDataset.put("datasetId", 8);
        otherDataset.put("keyword", "张");
        Map<String, Object> otherCard = filter("select", "status");
        otherCard.put("values", List.of("作废"));
        otherCard.put("scope", "cards");
        otherCard.put("cardIds", List.of(999));

        assertThat(DashboardFilters.conditions(List.of(mine, otherDataset, otherCard), 7L, 101L))
                .containsExactly(condition("status", "IN", List.of("已完成")));
    }
}
