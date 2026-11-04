package com.cdcplatform.service.account;

import com.cdcplatform.model.Account;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.Types.AccountType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StarRocks 有两个入口：查询走 MySQL 协议（9030），写入走 Stream Load（HTTP 8030）。
 * 表单里只填 Stream Load 地址时，查询主机必须能从它推导出来，否则 JDBC 会连到 null。
 */
class AccountAdapterTest {

    private final AccountAdapter adapter = new AccountAdapter();

    private static Account starrocks(Map<String, Object> extra) {
        return new Account(1L, "BI-SR", AccountType.STARROCKS, null, null, "ADS", "ROUSER", "pwd",
                true, extra, true, null, null);
    }

    @Test
    void derivesTheQueryHostFromTheStreamLoadEndpoint() {
        var source = adapter.toSource(starrocks(Map.of(
                "endpoint", "https://10.2.0.84",
                "queryPort", 9030)));

        assertThat(source.host()).isEqualTo("10.2.0.84");
        assertThat(source.port()).isEqualTo(9030);
    }

    @Test
    void keepsThePortOfTheEndpointOutOfTheHost() {
        var source = adapter.toSource(starrocks(Map.of(
                "endpoint", "http://10.2.0.84:8030/",
                "queryPort", 9031)));

        assertThat(source.host()).isEqualTo("10.2.0.84");
        assertThat(source.port()).isEqualTo(9031);
    }

    @Test
    void prefersAnExplicitQueryHost() {
        // 查询和写入在不同机器上是常见部署，显式填的地址优先
        var source = adapter.toSource(starrocks(Map.of(
                "endpoint", "http://10.2.0.85:8030",
                "queryHost", "fe.vip.internal",
                "queryPort", 9030)));

        assertThat(source.host()).isEqualTo("fe.vip.internal");
    }

    @Test
    void fallsBackToTheDefaultQueryPort() {
        SourceConfig source = adapter.toSource(starrocks(Map.of("endpoint", "http://10.2.0.84:8030")));

        assertThat(source.host()).isEqualTo("10.2.0.84");
        assertThat(source.port()).isEqualTo(9030);
    }
}
