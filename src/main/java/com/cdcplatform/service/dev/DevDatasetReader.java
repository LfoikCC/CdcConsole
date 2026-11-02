package com.cdcplatform.service.dev;

import com.cdcplatform.model.Account;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.Capability;
import com.cdcplatform.model.dataset.Dataset;
import com.cdcplatform.model.dataset.DatasetColumn;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.account.AccountAdapter;
import com.cdcplatform.service.account.AccountCapabilities;
import com.cdcplatform.service.dev.DevNodeReader.ReadResult;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 数据流里的数据集读写。
 *
 * <p>Excel 数据集物化在 {@code DATASET_STORE}，与运行期的 {@code DEV_TMP} 隔离；
 * 直连表 / SQL 数据集则实时读账户。
 */
@Service
public class DevDatasetReader {

    private final DatasetRepository datasets;
    private final ConfigRepository configs;
    private final AccountAdapter adapter;
    private final DevNodeReader reader;
    private final DevTargetWriter writer;
    private final DevTempTableStore runStore;
    private final DevTempTableStore datasetStore;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DevDatasetReader(DatasetRepository datasets, ConfigRepository configs, AccountAdapter adapter,
                            DevNodeReader reader, DevTargetWriter writer, DevTempTableStore runStore,
                            @Qualifier("datasetTableStore") DevTempTableStore datasetStore) {
        this.datasets = datasets;
        this.configs = configs;
        this.adapter = adapter;
        this.reader = reader;
        this.writer = writer;
        this.runStore = runStore;
        this.datasetStore = datasetStore;
    }

    public ReadResult read(long datasetId, long scope, String nodeKey, int maxRows) {
        Dataset dataset = datasets.dataset(datasetId);
        return switch (dataset.datasetType()) {
            case "EXCEL", "EXTRACT" -> copyFromStorage(dataset, scope, nodeKey, maxRows);
            case "TABLE" -> {
                var source = source(dataset.accountId());
                yield reader.readTable(source, dataset.databaseName(), dataset.tableName(), List.of(),
                        null, scope, nodeKey, maxRows);
            }
            case "SQL" -> {
                var source = source(dataset.accountId());
                yield reader.readSql(source, dataset.databaseName(), dataset.sqlText(), scope, nodeKey, maxRows);
            }
            default -> throw new IllegalArgumentException("未知的数据集类型：" + dataset.datasetType());
        };
    }

    public long write(long datasetId, String mode, ReadResult upstream) {
        Dataset dataset = datasets.dataset(datasetId);
        if ("SQL".equals(dataset.datasetType())) {
            throw new IllegalArgumentException("SQL 数据集是只读的，不能作为输出");
        }
        ReentrantLock lock = locks.computeIfAbsent(datasetId, key -> new ReentrantLock());
        lock.lock();
        try {
            if ("TABLE".equals(dataset.datasetType())) {
                Account account = configs.account(dataset.accountId(), true);
                AccountCapabilities.require(account.accountType(), Capability.TARGET, "作为数据集写入目标");
                TargetConfig target = adapter.toTarget(account, true);
                var result = writer.write(target, dataset.tableName(), DevTargetWriter.WriteMode.OVERWRITE,
                        upstream.columns(), upstream.table(), List.of(), List.of());
                return result.written();
            }
            return writeStorage(dataset, mode, upstream);
        } finally {
            lock.unlock();
        }
    }

    private ReadResult copyFromStorage(Dataset dataset, long scope, String nodeKey, int maxRows) {
        if (dataset.storageTable() == null) {
            throw new IllegalArgumentException("数据集「" + dataset.name() + "」还没有物化数据");
        }
        List<ColumnMeta> columns = columns(dataset.id());
        String target = runStore.tableName(scope, nodeKey);
        runStore.drop(target);
        runStore.create(target, columns);
        long rows = runStore.materialize(target, columns, selectAll(dataset.storageTable(), columns), List.of());
        return new ReadResult(target, rows, columns);
    }

    private long writeStorage(Dataset dataset, String mode, ReadResult upstream) {
        List<ColumnMeta> columns = upstream.columns();
        if (dataset.storageTable() == null) {
            throw new IllegalArgumentException("数据集「" + dataset.name() + "」没有物化表");
        }
        boolean overwrite = !"APPEND".equalsIgnoreCase(mode);
        if (overwrite) {
            datasetStore.drop(dataset.storageTable());
            datasetStore.create(dataset.storageTable(), columns);
        } else {
            List<ColumnMeta> existing = datasetStore.columnsOf(dataset.storageTable());
            if (existing.size() != columns.size()) {
                throw new IllegalArgumentException("追加写入要求列数一致：数据集 " + existing.size()
                        + " 列，上游 " + columns.size() + " 列");
            }
        }
        long rows = datasetStore.materialize(dataset.storageTable(), columns,
                selectAll(upstream.table(), columns), List.of());
        if (overwrite) {
            datasets.updateRowCount(dataset.id(), rows);
            datasets.saveColumns(dataset.id(), toDatasetColumns(columns));
        } else {
            datasets.updateRowCount(dataset.id(), dataset.rowCount() + rows);
        }
        return rows;
    }

    private List<ColumnMeta> columns(long datasetId) {
        List<ColumnMeta> columns = new ArrayList<>();
        for (DatasetColumn column : datasets.columns(datasetId)) {
            columns.add(new ColumnMeta(column.ordinal(), column.name(), column.dataType()));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("数据集没有字段");
        }
        return columns;
    }

    private static List<DatasetColumn> toDatasetColumns(List<ColumnMeta> columns) {
        List<DatasetColumn> result = new ArrayList<>();
        for (ColumnMeta column : columns) {
            result.add(new DatasetColumn(column.index(), column.label(), column.h2Type(), true));
        }
        return result;
    }

    private static String selectAll(String table, List<ColumnMeta> columns) {
        StringBuilder sql = new StringBuilder("SELECT \"ORD\"");
        for (ColumnMeta column : columns) {
            sql.append(", \"").append(column.physical()).append('"');
        }
        return sql.append(" FROM ").append(table).toString();
    }

    private com.cdcplatform.model.SourceConfig source(long accountId) {
        Account account = configs.account(accountId, true);
        AccountCapabilities.require(account.accountType(), Capability.QUERY, "读取数据集");
        return adapter.toSource(account);
    }
}
