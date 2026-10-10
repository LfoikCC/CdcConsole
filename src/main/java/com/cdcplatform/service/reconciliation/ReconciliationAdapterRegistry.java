package com.cdcplatform.service.reconciliation;

import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.model.Types.TargetType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ReconciliationAdapterRegistry {
    private final Map<String, ReconciliationDatabaseAdapter> adapters;
    public ReconciliationAdapterRegistry(List<ReconciliationDatabaseAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toMap(ReconciliationDatabaseAdapter::id, Function.identity()));
    }
    public ReconciliationDatabaseAdapter source(DatabaseType type) {
        return require(type == DatabaseType.STARROCKS ? "STARROCKS" : type.name());
    }
    public ReconciliationDatabaseAdapter target(TargetType type) {
        return require(type == TargetType.STARROCKS_STREAM_LOAD ? "STARROCKS" : type.name());
    }
    private ReconciliationDatabaseAdapter require(String id) {
        var adapter = adapters.get(id);
        if (adapter == null) throw new IllegalArgumentException("数据库类型暂不支持数据核对：" + id);
        return adapter;
    }
}
