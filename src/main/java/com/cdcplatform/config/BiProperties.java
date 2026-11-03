package com.cdcplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 数据分析模块的可调参数。 */
@Component
@ConfigurationProperties(prefix = "cdc.bi")
public class BiProperties {

    /** 单张卡片一次最多取多少行。 */
    private int maxRows = 5000;
    /** 单条聚合查询的超时秒数：没有它，一条坏 SQL 能钉住一条源库连接几分钟。 */
    private int queryTimeoutSeconds = 30;
    /** 一个看板最多多少张卡片。 */
    private int maxCardsPerDashboard = 40;

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMaxCardsPerDashboard() {
        return maxCardsPerDashboard;
    }

    public void setMaxCardsPerDashboard(int maxCardsPerDashboard) {
        this.maxCardsPerDashboard = maxCardsPerDashboard;
    }
}
