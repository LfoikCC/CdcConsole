package com.cdcplatform.service.dev;

import com.cdcplatform.model.dev.DevGraph;
import com.cdcplatform.model.dev.DevGraph.DevEdge;
import com.cdcplatform.model.dev.DevGraph.DevNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevGraphValidatorTest {

    private final DevGraphValidator validator = new DevGraphValidator();

    private static DevNode node(String id, String type, Map<String, Object> config) {
        return new DevNode(id, type, 0, 0, id, config);
    }

    private static Map<String, Object> inputConfig() {
        return Map.of("sourceId", 1, "database", "CDC-SOURCE", "table", "orders");
    }

    private static Map<String, Object> outputConfig() {
        return Map.of("targetId", 2, "table", "orders_copy", "mode", "OVERWRITE");
    }

    private static Map<String, Object> filterConfig() {
        return Map.of("operator", "FILTER",
                "conditions", List.of(Map.of("column", "id", "op", "=", "value", "1")));
    }

    @Test
    void acceptsLinearFlowAndOrdersIt() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", filterConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isTrue();
        assertThat(report.errors()).isEmpty();
        assertThat(report.order()).containsExactly("a", "b", "c");
    }

    @Test
    void rejectsCycle() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", filterConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c"), new DevEdge("c", "b")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        // 入度 >1 与成环都要报出来，前者是结构问题，后者是排序问题
        assertThat(report.errors()).anyMatch(i -> "edge".equals(i.field()));
        assertThat(report.order()).hasSizeLessThan(3);
    }

    @Test
    void rejectsGraphWithoutOutputNode() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", filterConfig())),
                List.of(new DevEdge("a", "b")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> i.message().contains("数据库输出"));
    }

    @Test
    void rejectsInputNodeWithUpstream() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "DB_INPUT", inputConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> "b".equals(i.nodeKey()) && i.message().contains("不能有上游"));
    }

    @Test
    void rejectsNodeWithoutUpstream() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of());

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> "c".equals(i.nodeKey()) && i.message().contains("缺少上游"));
    }

    @Test
    void rejectsUpsertWithoutKeyColumns() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("c", "DB_OUTPUT", Map.of("targetId", 2, "table", "orders_copy", "mode", "UPSERT"))),
                List.of(new DevEdge("a", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> "keyColumns".equals(i.field()));
    }

    @Test
    void acceptsDatabaseInputInSqlMode() {
        // SQL 输入并进数据库输入后：填了 SQL 就不该再要求选表
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", Map.of("sourceId", 1, "mode", "SQL",
                                "database", "CDC-SOURCE", "sql", "SELECT id FROM orders")),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "c")));

        assertThat(validator.validate(graph, true).valid()).isTrue();
    }

    @Test
    void stillRequiresTableOrSqlForDatabaseInput() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", Map.of("sourceId", 1, "mode", "SQL")),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> "sql".equals(i.field()));
    }

    @Test
    void skipsConfigChecksWhenNotForExecution() {
        // 只存草稿时不要求填完连接和表名，否则用户没法中途保存
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", Map.of()),
                        node("c", "DB_OUTPUT", Map.of())),
                List.of(new DevEdge("a", "c")));

        assertThat(validator.validate(graph, false).valid()).isTrue();
        assertThat(validator.validate(graph, true).valid()).isFalse();
    }

    @Test
    void detectsWouldCycleBeforeConnecting() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", filterConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        assertThat(validator.wouldCycle(graph, "c", "a")).isTrue();
        assertThat(validator.wouldCycle(graph, "a", "c")).isFalse();
        assertThat(validator.wouldCycle(graph, "a", "a")).isTrue();
    }

    @Test
    void acceptsLowercaseOperatorName() {
        // 以前 "filter" 能过、"Filter" 报错；现在统一按大写比对
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", Map.of("operator", "filter",
                                "conditions", List.of(Map.of("column", "id", "op", "=", "value", "1")))),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        assertThat(validator.validate(graph, true).valid()).isTrue();
    }

    @Test
    void rejectsAggregateWithoutAggregates() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", Map.of("operator", "AGGREGATE", "groupBy", List.of("id"))),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> i.message().contains("至少需要一个聚合项"));
    }

    @Test
    void rejectsCalculateWithUnbalancedParentheses() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", Map.of("operator", "CALCULATE",
                                "alias", "x", "expression", "amount * (1 + 2", "type", "DOUBLE")),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        // 括号不配平属于语法错误，不必等到运行才报
        assertThat(report.errors()).anyMatch(i -> "expression".equals(i.field()));
    }

    @Test
    void rejectsMergeColumnsWithSingleColumn() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", Map.of("operator", "MERGE_COLUMNS",
                                "columns", List.of("id"), "alias", "标识")),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> i.message().contains("至少需要两个字段"));
    }

    @Test
    void topologicalOrderIsStableForSameGraph() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "OPERATOR", filterConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "b"), new DevEdge("b", "c")));

        assertThat(validator.topologicalOrder(
                new java.util.LinkedHashSet<>(List.of("a", "b", "c")), graph.edges()))
                .containsExactly("a", "b", "c");
    }

    @Test
    void joinAcceptsTwoUpstreams() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "DB_INPUT", inputConfig()),
                        node("j", "OPERATOR", Map.of("operator", "JOIN",
                                "keys", List.of(Map.of("left", "id", "right", "id")))),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "j"), new DevEdge("b", "j"), new DevEdge("j", "c")));

        assertThat(validator.validate(graph, true).valid()).isTrue();
    }

    @Test
    void joinRejectsSingleUpstream() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("j", "OPERATOR", Map.of("operator", "JOIN",
                                "keys", List.of(Map.of("left", "id", "right", "id")))),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "j"), new DevEdge("j", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> i.message().contains("需要两个上游"));
    }

    @Test
    void singleInputOperatorStillRejectsSecondUpstream() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("b", "DB_INPUT", inputConfig()),
                        node("f", "OPERATOR", filterConfig()),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "f"), new DevEdge("b", "f"), new DevEdge("f", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> i.message().contains("只接受一条入边"));
    }

    @Test
    void sqlOperatorRequiresInputPlaceholder() {
        var graph = new DevGraph(
                List.of(node("a", "DB_INPUT", inputConfig()),
                        node("q", "OPERATOR", Map.of("operator", "SQL", "sql", "SELECT * FROM t")),
                        node("c", "DB_OUTPUT", outputConfig())),
                List.of(new DevEdge("a", "q"), new DevEdge("q", "c")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> "sql".equals(i.field()) && i.message().contains("{{input}}"));
    }

    @Test
    void datasetInputAndOutputAreValidWithoutDatabaseAccounts() {
        var graph = new DevGraph(
                List.of(node("d1", "DATASET_INPUT", Map.of("datasetId", 1)),
                        node("d2", "DATASET_OUTPUT", Map.of("datasetId", 2, "mode", "OVERWRITE"))),
                List.of(new DevEdge("d1", "d2")));

        assertThat(validator.validate(graph, true).valid()).isTrue();
    }

    @Test
    void datasetInputRequiresDatasetId() {
        var graph = new DevGraph(
                List.of(node("d1", "DATASET_INPUT", Map.of()),
                        node("d2", "DATASET_OUTPUT", Map.of("datasetId", 2))),
                List.of(new DevEdge("d1", "d2")));

        var report = validator.validate(graph, true);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).anyMatch(i -> "datasetId".equals(i.field()));
    }
}
