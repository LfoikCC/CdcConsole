package com.cdcplatform.api;

import com.cdcplatform.model.Account;
import com.cdcplatform.model.PageResult;
import com.cdcplatform.model.Types.AccountType;
import com.cdcplatform.model.Types.Capability;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.service.ConnectionProbeService;
import com.cdcplatform.service.account.AccountAdapter;
import com.cdcplatform.service.account.AccountCapabilities;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统一数据账户。源/目标不再是两种资源，能力由账户类型推导。 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    public record AccountInput(Long id, String name, AccountType accountType, String host, Integer port,
                               String databaseName, String username, String password,
                               Map<String, Object> extra, Boolean enabled) {
    }

    private final ConfigRepository repository;
    private final ConnectionProbeService probe;
    private final AccountAdapter adapter;

    public AccountController(ConfigRepository repository, ConnectionProbeService probe, AccountAdapter adapter) {
        this.repository = repository;
        this.probe = probe;
        this.adapter = adapter;
    }

    @GetMapping
    Object accounts(@RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "200") int size,
                    @RequestParam(required = false) String type) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Account account : repository.accounts()) {
            if (type == null || type.isBlank() || account.accountType().name().equalsIgnoreCase(type)) {
                rows.add(view(account));
            }
        }
        return PageResult.of(rows, page, size);
    }

    @GetMapping("/{id}")
    Object account(@PathVariable long id) {
        return view(repository.account(id, false));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestBody AccountInput input) {
        validate(input);
        return Map.of("id", repository.addAccount(toAccount(null, input)));
    }

    @PutMapping("/{id}")
    void update(@PathVariable long id, @RequestBody AccountInput input) {
        validate(input);
        repository.updateAccount(id, toAccount(id, input));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        repository.deleteAccount(id);
    }

    @PostMapping("/{id}/probe")
    Object probe(@PathVariable long id) {
        Account account = repository.account(id, true);
        if (account.accountType() == AccountType.KAFKA) {
            int topics = probe.topics(adapter.toTarget(account, true)).size();
            return Map.of("reachable", true, "detail", "Kafka 连接正常，发现 " + topics + " 个 Topic");
        }
        return probe.probe(adapter.toSource(account));
    }

    /** 新建/编辑弹窗里的「测试连接」：用表单里的值直接试，不落库。 */
    @PostMapping("/probe")
    Object probeDraft(@RequestBody AccountInput input) {
        validateConnection(input);
        Account account = draftAccount(input);
        if (account.accountType() == AccountType.KAFKA) {
            int topics = probe.topics(adapter.toTarget(account, true)).size();
            return Map.of("reachable", true, "detail", "Kafka 连接正常，发现 " + topics + " 个 Topic");
        }
        return probe.probe(adapter.toSource(account));
    }

    @GetMapping("/{id}/databases")
    Object databases(@PathVariable long id) {
        return probe.databases(adapter.toSource(repository.account(id, true)));
    }

    @GetMapping("/{id}/tables")
    Object tables(@PathVariable long id, @RequestParam(required = false) String database) {
        var source = adapter.toSource(repository.account(id, true));
        String selected = database == null || database.isBlank() ? source.databaseName() : database;
        return probe.tables(source, selected).stream().map(table -> {
            var result = new LinkedHashMap<>(table);
            result.put("database", selected);
            result.put("capability", source.dbType() == com.cdcplatform.model.Types.DatabaseType.STARROCKS
                    ? "POLLING_ONLY" : "STRICT_CDC");
            return result;
        }).toList();
    }

    @GetMapping("/{id}/topics")
    Object topics(@PathVariable long id) {
        return probe.topics(adapter.toTarget(repository.account(id, true), true));
    }

    private Map<String, Object> view(Account account) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", account.id());
        row.put("name", account.name());
        row.put("accountType", account.accountType());
        row.put("host", account.host());
        row.put("port", account.port());
        row.put("databaseName", account.databaseName());
        row.put("username", account.username());
        row.put("hasPassword", account.hasPassword());
        row.put("extra", account.extra());
        row.put("enabled", account.enabled());
        row.put("capabilities", AccountCapabilities.of(account.accountType()));
        row.put("createdAt", account.createdAt());
        row.put("updatedAt", account.updatedAt());
        return row;
    }

    private Account toAccount(Long id, AccountInput input) {
        Map<String, Object> extra = input.extra() == null ? Map.of() : input.extra();
        return new Account(id, input.name().trim(), input.accountType(), input.host(), input.port(),
                input.databaseName(), input.username(), input.password(),
                input.password() != null && !input.password().isBlank(), extra,
                input.enabled() == null || input.enabled(), null, null);
    }

    private void validate(AccountInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("请填写账户名称");
        }
        validateConnection(input);
    }

    /** 只校验连接参数：测试连接时还没填名字也应该能试。 */
    private void validateConnection(AccountInput input) {
        if (input.accountType() == null) {
            throw new IllegalArgumentException("请选择账户类型");
        }
        if (input.accountType() == AccountType.KAFKA) {
            Object servers = input.extra() == null ? null : input.extra().get("bootstrapServers");
            if (servers == null || String.valueOf(servers).isBlank()) {
                throw new IllegalArgumentException("请填写 Kafka 的 bootstrapServers");
            }
            return;
        }
        if (input.accountType() == AccountType.STARROCKS) {
            Object endpoint = input.extra() == null ? null : input.extra().get("endpoint");
            if (endpoint == null || String.valueOf(endpoint).isBlank()) {
                throw new IllegalArgumentException("请填写 StarRocks 的访问地址");
            }
        } else if (input.host() == null || input.host().isBlank()) {
            throw new IllegalArgumentException("请填写数据库地址");
        }
        if (input.username() == null || input.username().isBlank()) {
            throw new IllegalArgumentException("请填写用户名");
        }
        AccountCapabilities.require(input.accountType(), Capability.TARGET, "保存");
    }

    /** 测试连接用的临时账户：编辑时密码留空要沿用已保存的密码。 */
    private Account draftAccount(AccountInput input) {
        Account base = toAccount(input.id(), input);
        if (input.id() == null) {
            return base;
        }
        Account current = repository.account(input.id(), true);
        String password = input.password();
        if (password == null || password.isBlank() || "******".equals(password)) {
            password = current.password();
        }
        return new Account(base.id(), base.name(), base.accountType(), base.host(), base.port(),
                base.databaseName(), base.username(), password,
                password != null && !password.isBlank(), base.extra(), base.enabled(),
                base.createdAt(), base.updatedAt());
    }
}
