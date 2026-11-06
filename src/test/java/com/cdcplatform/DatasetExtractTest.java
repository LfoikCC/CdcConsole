package com.cdcplatform;

import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.dataset.DatasetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataset-extract-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-dataset-extract-cache"
})
class DatasetExtractTest {

    @Autowired
    DatasetService datasets;
    @Autowired
    DatasetRepository repository;

    @Test
    void createsEmptyOfflineDatasetAndExplainsMissingData() {
        long id = datasets.createExtract("离线数据集", "flow output", "admin");

        var dataset = repository.dataset(id);
        assertThat(dataset.datasetType()).isEqualTo("EXTRACT");
        assertThat(dataset.storageTable()).isNotBlank();
        assertThat(dataset.rowCount()).isZero();
        assertThat(dataset.columnCount()).isZero();

        assertThatThrownBy(() -> datasets.rows(id, 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("还没有数据");

        datasets.delete(id);
    }
}
