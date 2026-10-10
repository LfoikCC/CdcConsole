package com.cdcplatform.service.reconciliation;

import com.cdcplatform.model.Types.ReconciliationMetric;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationAdapterTest {
    @Test void mysqlQuotesIdentifiersAndBuildsMetrics() {
        var adapter = new MySqlReconciliationAdapter();

        assertThat(adapter.qualified("sales", "orders")).isEqualTo("`sales`.`orders`");
        assertThat(adapter.aggregateExpression(ReconciliationMetric.ROW_COUNT, "*")).isEqualTo("COUNT(*)");
        assertThat(adapter.aggregateExpression(ReconciliationMetric.NULL_RATE, "`amount`")).contains("IS NULL");
        assertThatThrownBy(() -> adapter.quote("orders;drop table x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sqlServerUsesBracketsBigCountsAndSafeBatchSize() {
        var adapter = new SqlServerReconciliationAdapter();

        assertThat(adapter.qualified("dbo", "orders")).isEqualTo("[dbo].[orders]");
        assertThat(adapter.aggregateExpression(ReconciliationMetric.ROW_COUNT, "*")).isEqualTo("COUNT_BIG(*)");
        assertThat(adapter.batchSize(3)).isEqualTo(633);
    }

    @Test void starRocksReusesMysqlProtocolDialect() {
        var adapter = new StarRocksReconciliationAdapter();

        assertThat(adapter.id()).isEqualTo("STARROCKS");
        assertThat(adapter.quote("order_id")).isEqualTo("`order_id`");
    }
}
