package com.cdcplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 数据集：Excel 文件目录与解析上限。 */
@Configuration
@ConfigurationProperties(prefix = "cdc.dataset")
public class DatasetProperties {

    private String directory = "./data/datasets";
    private String storageSchema = "DATASET_STORE";
    private int maxRows = 100_000;
    private int maxColumns = 200;
    private int maxSheets = 20;

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getStorageSchema() {
        return storageSchema;
    }

    public void setStorageSchema(String storageSchema) {
        this.storageSchema = storageSchema;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public void setMaxColumns(int maxColumns) {
        this.maxColumns = maxColumns;
    }

    public int getMaxSheets() {
        return maxSheets;
    }

    public void setMaxSheets(int maxSheets) {
        this.maxSheets = maxSheets;
    }

    public Path directoryPath() {
        return Path.of(directory).toAbsolutePath().normalize();
    }
}
