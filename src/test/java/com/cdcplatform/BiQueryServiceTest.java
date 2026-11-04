package com.cdcplatform;

import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.bi.BiQueryBuilder.CardConfig;
import com.cdcplatform.service.bi.BiQueryBuilder.Dimension;
import com.cdcplatform.service.bi.BiQueryBuilder.Filter;
import com.cdcplatform.service.bi.BiQueryBuilder.Metric;
import com.cdcplatform.service.bi.BiQueryBuilder.Sort;
import com.cdcplatform.service.bi.BiQueryService;
import com.cdcplatform.service.dataset.DatasetService;
import com.cdcplatform.service.dev.DevTempTableStore;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图表取数的端到端用例：离线数据集（物理列 C1..Cn）→ 聚合查询 → 列名与数值。
 *
 * <p>这条链路是整个数据分析模块最容易回归的地方：
 * 物理列名不是真实列名，写错了只会得到「Column not found」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bi-query-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-bi-query-cache"
})
class BiQueryServiceTest {

    @Autowired
    BiQueryService biQuery;
    @Autowired
    DatasetService datasets;
    @Autowired
    DatasetRepository repository;
    @Autowired
    @Qualifier("datasetTableStore")
    DevTempTableStore store;
    @Autowired
    DataSource dataSource;

    private long seedDataset() throws Exception {
        long id = datasets.createExtract("销售明细", "", "admin");
        String table = repository.dataset(id).storageTable();
        store.create(table, List.of(
                new ColumnMeta(1, "region", "VARCHAR"),
                new ColumnMeta(2, "amount", "DECIMAL(38,10)")));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO " + table + " (\"ORD\",\"C1\",\"C2\") VALUES "
                    + "(1,'华东',120), (2,'华北',80), (3,'华东',100)");
        }
        return id;
    }

    @Test
    void aggregatesAnOfflineDatasetByItsPhysicalColumns() throws Exception {
        long id = seedDataset();

        var result = biQuery.query(id, new CardConfig(
                List.of(new Dimension("region")),
                List.of(new Metric("amount", "SUM", "销售额")),
                List.of(),
                new Sort("metric", 0, "desc"),
                100));

        assertThat(result.columns()).containsExactly("region", "销售额");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("华东", new BigDecimal("220.0000000000"));
        assertThat(result.rows().get(1)).containsExactly("华北", new BigDecimal("80.0000000000"));
        assertThat(result.truncated()).isFalse();

        datasets.delete(id);
    }

    @Test
    void appliesFiltersAndRowLimit() throws Exception {
        long id = seedDataset();

        var filtered = biQuery.query(id, new CardConfig(
                List.of(new Dimension("region")),
                List.of(new Metric("amount", "SUM", "销售额")),
                List.of(new Filter("region", "=", "华北")),
                null, 100));
        assertThat(filtered.rows()).hasSize(1);
        assertThat(filtered.rows().get(0).get(0)).isEqualTo("华北");

        // 两个分组只取 1 行：结果应标记为被截断
        var limited = biQuery.query(id, new CardConfig(
                List.of(new Dimension("region")),
                List.of(new Metric("amount", "SUM", null)), List.of(), null, 1));
        assertThat(limited.rows()).hasSize(1);
        assertThat(limited.truncated()).isTrue();

        datasets.delete(id);
    }

    @Test
    void listsDistinctValuesForFiltersAndCustomSort() throws Exception {
        long id = seedDataset();

        var values = biQuery.values(id, "region", 100);

        assertThat(values.values()).containsExactlyInAnyOrder("华东", "华北");
        assertThat(values.truncated()).isFalse();
        // 库表数据集的元数据里行数是 0，这里按需现算
        assertThat(biQuery.count(id)).isEqualTo(3);

        datasets.delete(id);
    }

    @Test
    void explainsWhenTheOfflineDatasetHasNoData() {
        long id = datasets.createExtract("空数据集", "", "admin");

        assertThatThrownBy(() -> biQuery.query(id, CardConfig.of(
                List.of(new Dimension("region")), List.of(new Metric("amount", "SUM", null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("还没有物化数据");

        datasets.delete(id);
    }
}
