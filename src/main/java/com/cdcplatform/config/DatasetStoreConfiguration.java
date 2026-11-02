package com.cdcplatform.config;

import com.cdcplatform.service.dev.DevTempTableStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 数据集的物化表放在独立 schema。
 *
 * <p>不能复用 {@code DEV_TMP}：数据流每次运行结束都会 {@code dropAll()}，
 * 数据集落在那里会被顺手清空。
 */
@Configuration
public class DatasetStoreConfiguration {

    @Bean("datasetTableStore")
    public DevTempTableStore datasetTableStore(DataSource dataSource, DevProperties dev, DatasetProperties dataset) {
        DevProperties props = new DevProperties();
        props.setTempSchema(dataset.getStorageSchema());
        props.setBatchSize(dev.getBatchSize());
        // 数据集是持久资产，启动时不能像 DEV_TMP 那样清空
        props.setCleanupOnStartup(false);
        return new DevTempTableStore(dataSource, props);
    }
}
