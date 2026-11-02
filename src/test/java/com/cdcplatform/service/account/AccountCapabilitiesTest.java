package com.cdcplatform.service.account;

import com.cdcplatform.model.Types.AccountType;
import com.cdcplatform.model.Types.Capability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountCapabilitiesTest {

    @Test
    void mysqlAndSqlServerCanBeSourceTargetAndQuery() {
        assertThat(AccountCapabilities.of(AccountType.MYSQL))
                .containsExactlyInAnyOrder(Capability.SOURCE, Capability.TARGET, Capability.QUERY);
        assertThat(AccountCapabilities.of(AccountType.SQLSERVER))
                .containsExactlyInAnyOrder(Capability.SOURCE, Capability.TARGET, Capability.QUERY);
    }

    @Test
    void kafkaCanOnlyBeTarget() {
        assertThat(AccountCapabilities.of(AccountType.KAFKA)).containsExactly(Capability.TARGET);
        assertThatThrownBy(() -> AccountCapabilities.require(AccountType.KAFKA, Capability.SOURCE, "作为同步源"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能作为同步源");
    }

    @Test
    void starrocksKeepsSourceForPolling() {
        assertThat(AccountCapabilities.can(AccountType.STARROCKS, Capability.SOURCE)).isTrue();
        assertThat(AccountCapabilities.can(AccountType.STARROCKS, Capability.QUERY)).isTrue();
    }
}
