package com.cdcplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 数据开发（可视化数据流）的运行参数。
 *
 * <p>行数上限不是保守，是防止一条误配的数据流把 H2 文件写到几个 GB：
 * 中间结果全部落在同一个嵌入式库里，没有兜底就是磁盘事故。
 */
@Configuration
@ConfigurationProperties(prefix = "cdc.dev")
public class DevProperties {

    /** 单个节点输出的行数上限，超出直接失败而不是静默截断。 */
    private int maxRowsPerNode = 2_000_000;
    /** 写入临时表与目标表时的批大小。 */
    private int batchSize = 1000;
    /** 预览时输入节点最多扫描多少行。 */
    private int previewScanRows = 5000;
    /** 预览返回多少行。 */
    private int previewRows = 200;
    /** 同时运行的数据流数量上限，保护 H2 与目标库连接池。 */
    private int maxConcurrentRuns = 2;
    /** 中间结果所在的 schema。 */
    private String tempSchema = "DEV_TMP";

    public int getMaxRowsPerNode() {
        return maxRowsPerNode;
    }

    public void setMaxRowsPerNode(int maxRowsPerNode) {
        this.maxRowsPerNode = maxRowsPerNode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getPreviewScanRows() {
        return previewScanRows;
    }

    public void setPreviewScanRows(int previewScanRows) {
        this.previewScanRows = previewScanRows;
    }

    public int getPreviewRows() {
        return previewRows;
    }

    public void setPreviewRows(int previewRows) {
        this.previewRows = previewRows;
    }

    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = maxConcurrentRuns;
    }

    public String getTempSchema() {
        return tempSchema;
    }

    public void setTempSchema(String tempSchema) {
        this.tempSchema = tempSchema;
    }
}
