package com.cdcplatform.model;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 统一数据账户：一个账户既可能在同步任务里当源，也可能当目标，角色由 {@link Types.AccountType} 的能力决定。
 *
 * <p>{@code password} 默认不下发，只给 {@code hasPassword}；需要真实密码的内部调用用 includeSecrets。
 */
public record Account(Long id, String name, Types.AccountType accountType,
                      String host, Integer port, String databaseName,
                      String username, String password, boolean hasPassword,
                      Map<String, Object> extra, boolean enabled,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
