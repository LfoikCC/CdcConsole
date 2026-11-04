package com.cdcplatform.service.account;

import com.cdcplatform.model.Account;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.AccountType;
import com.cdcplatform.model.Types.Capability;
import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.model.Types.TargetType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一账户与既有 {@link SourceConfig}/{@link TargetConfig} 之间的适配层。
 *
 * <p>CDC 引擎、任务仓储、数据开发都还按源/目标两个模型编程，改造面太大；
 * 这里把「一个账户」投影成两种视图，引擎代码零改动。
 */
@Component
public class AccountAdapter {

    public SourceConfig toSource(Account account) {
        AccountCapabilities.require(account.accountType(), Capability.SOURCE, "作为同步源");
        Map<String, Object> extra = account.extra() == null ? Map.of() : account.extra();
        String host = account.host();
        int port = account.port() == null ? defaultPort(account.accountType()) : account.port();
        if (account.accountType() == AccountType.STARROCKS) {
            // StarRocks 有两个入口：查询走 MySQL 协议（默认 9030），写入走 Stream Load（HTTP 8030）。
            // 表单里填的是 Stream Load 地址，查询主机默认从它推导，也可以单独指定。
            host = firstNonBlank(text(extra.get("queryHost")), host, hostOfEndpoint(text(extra.get("endpoint"))));
            Integer queryPort = number(extra.get("queryPort"));
            port = queryPort == null ? port : queryPort;
        }
        return new SourceConfig(account.id(), account.name(), databaseType(account.accountType()),
                host, port, account.databaseName() == null ? "" : account.databaseName(),
                account.username(), account.password() == null ? "" : account.password(),
                pollInterval(account), account.enabled(), account.createdAt());
    }

    public TargetConfig toTarget(Account account, boolean includeSecrets) {
        AccountCapabilities.require(account.accountType(), Capability.TARGET, "作为同步目标");
        return new TargetConfig(account.id(), account.name(), targetType(account.accountType()),
                configOf(account, includeSecrets), account.enabled(), account.createdAt());
    }

    public Account fromSource(SourceConfig source) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("pollIntervalMs", source.pollIntervalMs());
        return new Account(source.id(), source.name(), accountType(source.dbType()),
                source.host(), source.port(), source.databaseName(), source.username(), source.password(),
                source.password() != null && !source.password().isBlank(), extra, source.enabled(),
                source.createdAt(), source.createdAt());
    }

    public Account fromTarget(TargetConfig target) {
        Map<String, Object> config = target.config() == null ? Map.of() : target.config();
        Map<String, Object> extra = new LinkedHashMap<>();
        AccountType type = accountType(target.targetType());
        String host = text(config.get("host"));
        Integer port = number(config.get("port"));
        String database = text(config.get("database"));
        if (type == AccountType.STARROCKS) {
            extra.put("endpoint", text(config.get("endpoint")));
            extra.put("queryHost", text(config.get("queryHost")));
            extra.put("queryPort", number(config.get("queryPort")));
        }
        if (type == AccountType.KAFKA) {
            extra.put("bootstrapServers", text(config.get("bootstrapServers")));
            putIfPresent(extra, "saslUsername", text(config.get("saslUsername")));
            putIfPresent(extra, "saslPassword", text(config.get("saslPassword")));
        }
        String password = text(config.get("password"));
        return new Account(target.id(), target.name(), type, host, port, database,
                text(config.get("username")), password, password != null && !password.isBlank(),
                extra, target.enabled(), target.createdAt(), target.createdAt());
    }

    public Map<String, Object> configOf(Account account, boolean includeSecrets) {
        Map<String, Object> config = new LinkedHashMap<>();
        AccountType type = account.accountType();
        if (type == AccountType.KAFKA) {
            config.put("bootstrapServers", account.extra() == null ? null : account.extra().get("bootstrapServers"));
            putIfPresent(config, "saslUsername", account.extra() == null ? null : account.extra().get("saslUsername"));
            if (account.extra() != null && account.extra().get("saslPassword") != null) {
                config.put("saslPassword", includeSecrets ? account.extra().get("saslPassword") : "******");
            }
            return config;
        }
        if (type == AccountType.STARROCKS) {
            config.put("endpoint", account.extra() == null ? null : account.extra().get("endpoint"));
            putIfPresent(config, "queryHost", account.extra() == null ? null : account.extra().get("queryHost"));
            config.put("queryPort", account.extra() == null ? null : account.extra().get("queryPort"));
        } else {
            config.put("host", account.host());
            config.put("port", account.port());
        }
        config.put("database", account.databaseName());
        config.put("username", account.username());
        if (account.hasPassword()) {
            config.put("password", includeSecrets ? account.password() : "******");
        }
        return config;
    }

    public DatabaseType databaseType(AccountType type) {
        return switch (type) {
            case MYSQL -> DatabaseType.MYSQL;
            case SQLSERVER -> DatabaseType.SQLSERVER;
            case STARROCKS -> DatabaseType.STARROCKS;
            case KAFKA -> throw new IllegalArgumentException("Kafka 账户不能作为同步源");
        };
    }

    public TargetType targetType(AccountType type) {
        return switch (type) {
            case MYSQL -> TargetType.MYSQL;
            case SQLSERVER -> TargetType.SQLSERVER;
            case STARROCKS -> TargetType.STARROCKS_STREAM_LOAD;
            case KAFKA -> TargetType.KAFKA;
        };
    }

    public AccountType accountType(DatabaseType type) {
        return switch (type) {
            case MYSQL -> AccountType.MYSQL;
            case SQLSERVER -> AccountType.SQLSERVER;
            case STARROCKS -> AccountType.STARROCKS;
        };
    }

    public AccountType accountType(TargetType type) {
        return switch (type) {
            case MYSQL -> AccountType.MYSQL;
            case SQLSERVER -> AccountType.SQLSERVER;
            case STARROCKS_STREAM_LOAD -> AccountType.STARROCKS;
            case KAFKA -> AccountType.KAFKA;
        };
    }

    private int defaultPort(AccountType type) {
        return switch (type) {
            case SQLSERVER -> 1433;
            case STARROCKS -> 9030;
            default -> 3306;
        };
    }

    private int pollInterval(Account account) {
        Object value = account.extra() == null ? null : account.extra().get("pollIntervalMs");
        Integer interval = number(value);
        return interval == null || interval < 100 ? 500 : interval;
    }

    /** 从 http(s)://host:port/xxx 里抠出主机名；填错了就返回 null，让上层报"请填写地址"。 */
    static String hostOfEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String value = endpoint.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            value = value.substring(0, colon);
        }
        return value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
