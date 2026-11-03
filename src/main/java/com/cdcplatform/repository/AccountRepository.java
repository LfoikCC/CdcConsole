package com.cdcplatform.repository;

import com.cdcplatform.model.Account;
import com.cdcplatform.model.Types.AccountType;
import com.cdcplatform.model.Types.Capability;
import com.cdcplatform.service.account.AccountCapabilities;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统一数据账户的持久层。源/目标两种旧模型都由 {@code AccountAdapter} 投影出来。 */
@Repository
public class AccountRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AccountRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Account> accounts(boolean includeSecrets) {
        return jdbc.sql("SELECT * FROM cdc_account ORDER BY id")
                .query((rs, n) -> read(rs, includeSecrets)).list();
    }

    public List<Account> accountsByCapability(Capability capability, boolean includeSecrets) {
        return accounts(includeSecrets).stream()
                .filter(account -> AccountCapabilities.can(account.accountType(), capability))
                .toList();
    }

    public Account account(long id, boolean includeSecrets) {
        return jdbc.sql("SELECT * FROM cdc_account WHERE id=:id").param("id", id)
                .query((rs, n) -> read(rs, includeSecrets)).optional()
                .orElseThrow(() -> new IllegalArgumentException("数据账户 " + id + " 不存在"));
    }

    public long add(Account account) {
        long id = nextId();
        jdbc.sql("INSERT INTO cdc_account(id,name,account_type,host,port,database_name,username,password," +
                        "extra_json,enabled,created_at,updated_at) " +
                        "VALUES(:id,:name,:type,:host,:port,:db,:user,:password,:extra,:enabled,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("id", id).param("name", account.name()).param("type", account.accountType().name())
                .param("host", account.host()).param("port", account.port()).param("db", account.databaseName())
                .param("user", account.username()).param("password", account.password())
                .param("extra", write(account.extra())).param("enabled", account.enabled())
                .update();
        return id;
    }

    public void update(long id, Account account) {
        Account current = account(id, true);
        String password = account.password();
        if (password == null || password.isBlank() || "******".equals(password)) {
            password = current.password();
        }
        Map<String, Object> extra = account.extra() == null ? Map.of() : account.extra();
        jdbc.sql("UPDATE cdc_account SET name=:name,account_type=:type,host=:host,port=:port,database_name=:db," +
                        "username=:user,password=:password,extra_json=:extra,enabled=:enabled,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("name", account.name()).param("type", account.accountType().name())
                .param("host", account.host()).param("port", account.port()).param("db", account.databaseName())
                .param("user", account.username()).param("password", password)
                .param("extra", write(extra)).param("enabled", account.enabled())
                .update();
    }

    public void delete(long id) {
        account(id, false);
        // 先清废弃的表映射，再做占用检查。
        // CaptureGroupService 启动时会为 table_mapping 里的每条映射重建采集组与订阅，
        // 若把这一步放在检查之后，映射会在下次启动时把订阅又造出来，账户就永远删不掉。
        jdbc.sql("DELETE FROM table_mapping WHERE source_id=:id OR target_id=:id").param("id", id).update();
        Map<String, Long> blocking = blockingUsage(id);
        if (!blocking.isEmpty()) {
            throw new IllegalStateException("数据账户仍被使用（" + describe(blocking) + "），请先解除引用");
        }
        jdbc.sql("DELETE FROM cdc_account WHERE id=:id").param("id", id).update();
    }

    /** 未取消的订阅才算占用；cancel 只改状态不删行，见 blockingUsage 的说明。 */
    private static final String LIVE_SUBSCRIPTION =
            "SELECT COUNT(*) FROM cdc_target_subscription WHERE status<>'CANCELLED'";
    private static final String LIVE_TASK_SUBSCRIPTION =
            "SELECT COUNT(*) FROM task_target_subscription WHERE status<>'CANCELLED'";

    /**
     * 真正阻止删除的引用：任务、采集组、订阅。表映射属于废弃数据，直接清理。
     *
     * <p>订阅只算「还活着」的那些：界面上删除订阅走的是 cancel，只把状态改成 CANCELLED 而不删行，
     * 若把 CANCELLED 也算作占用，用户取消订阅之后账户就永远删不掉了。
     */
    private Map<String, Long> blockingUsage(long id) {
        Map<String, Long> usage = new LinkedHashMap<>();
        count(usage, "同步任务", "SELECT COUNT(*) FROM sync_task WHERE source_id=:id OR target_id=:id", id);
        count(usage, "采集组", "SELECT COUNT(*) FROM cdc_capture_group WHERE source_id=:id", id);
        count(usage, "目标订阅", LIVE_SUBSCRIPTION + " AND target_id=:id", id);
        count(usage, "任务目标订阅", LIVE_TASK_SUBSCRIPTION + " AND target_id=:id", id);
        return usage;
    }

    /** 五张引用表的计数，只返回非零项，用于删除前的提示。 */
    public Map<String, Long> usage(long id) {
        Map<String, Long> usage = new LinkedHashMap<>();
        count(usage, "同步任务", "SELECT COUNT(*) FROM sync_task WHERE source_id=:id OR target_id=:id", id);
        count(usage, "表映射", "SELECT COUNT(*) FROM table_mapping WHERE source_id=:id OR target_id=:id", id);
        count(usage, "采集组", "SELECT COUNT(*) FROM cdc_capture_group WHERE source_id=:id", id);
        count(usage, "目标订阅", LIVE_SUBSCRIPTION + " AND target_id=:id", id);
        count(usage, "任务目标订阅", LIVE_TASK_SUBSCRIPTION + " AND target_id=:id", id);
        return usage;
    }

    /** 运行中的同步任务数：改账户配置前必须为 0。 */
    public long activeTasks(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM sync_task WHERE (source_id=:id OR target_id=:id) " +
                        "AND status NOT IN ('DRAFT','STOPPED')")
                .param("id", id).query(Long.class).single();
    }

    private void count(Map<String, Long> usage, String label, String sql, long id) {
        long value = jdbc.sql(sql).param("id", id).query(Long.class).single();
        if (value > 0) {
            usage.put(label, value);
        }
    }

    private String describe(Map<String, Long> usage) {
        return usage.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 个")
                .reduce((a, b) -> a + "、" + b).orElse("");
    }

    private long nextId() {
        return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();
    }

    private Account read(ResultSet rs, boolean includeSecrets) throws SQLException {
        String password = rs.getString("password");
        boolean hasPassword = password != null && !password.isBlank();
        return new Account(rs.getLong("id"), rs.getString("name"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("host"), (Integer) rs.getObject("port", Integer.class),
                rs.getString("database_name"), rs.getString("username"),
                includeSecrets ? password : null, hasPassword,
                readMap(rs.getString("extra_json")), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("账户扩展配置不是合法 JSON", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("账户扩展配置无法序列化", e);
        }
    }
}
