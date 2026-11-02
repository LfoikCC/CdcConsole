package com.cdcplatform.model.dataset;

import java.time.OffsetDateTime;

/** 数据集：TABLE（直连表）/ SQL（自定义查询）/ EXCEL（上传物化）。 */
public record Dataset(Long id, String name, String description, String datasetType,
                      Long accountId, String databaseName, String tableName, String sqlText,
                      String storageSchema, String storageTable, long rowCount, int columnCount,
                      String status, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
