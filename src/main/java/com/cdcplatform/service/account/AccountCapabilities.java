package com.cdcplatform.service.account;

import com.cdcplatform.model.Types.AccountType;
import com.cdcplatform.model.Types.Capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 账户能力矩阵。
 *
 * <p>能力不落库：类型决定一切，落库只会出现「类型改了、能力没改」的漂移。
 *
 * <p>StarRocks 仍然保留 SOURCE 能力——历史上它就能作为轮询源（POLLING_ONLY），
 * 去掉会直接让存量任务失效；真正不能当源的是 Kafka。
 */
public final class AccountCapabilities {

    private static final Map<AccountType, Set<Capability>> MATRIX = Map.of(
            AccountType.MYSQL, unmodifiable(EnumSet.of(Capability.SOURCE, Capability.TARGET, Capability.QUERY)),
            AccountType.SQLSERVER, unmodifiable(EnumSet.of(Capability.SOURCE, Capability.TARGET, Capability.QUERY)),
            AccountType.STARROCKS, unmodifiable(EnumSet.of(Capability.SOURCE, Capability.TARGET, Capability.QUERY)),
            AccountType.KAFKA, unmodifiable(EnumSet.of(Capability.TARGET))
    );

    private AccountCapabilities() {
    }

    public static Set<Capability> of(AccountType type) {
        if (type == null) {
            return Set.of();
        }
        return MATRIX.getOrDefault(type, Set.of());
    }

    public static boolean can(AccountType type, Capability capability) {
        return of(type).contains(capability);
    }

    public static void require(AccountType type, Capability capability, String action) {
        if (!can(type, capability)) {
            throw new IllegalArgumentException(type + " 类型的账户不能" + action);
        }
    }

    private static Set<Capability> unmodifiable(Set<Capability> values) {
        return Collections.unmodifiableSet(values);
    }
}
