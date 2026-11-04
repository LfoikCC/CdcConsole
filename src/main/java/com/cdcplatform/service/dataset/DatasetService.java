package com.cdcplatform.service.dataset;

import com.cdcplatform.config.DatasetProperties;
import com.cdcplatform.model.Account;
import com.cdcplatform.model.Types.Capability;
import com.cdcplatform.model.dataset.Dataset;
import com.cdcplatform.model.dataset.DatasetColumn;
import com.cdcplatform.repository.BiDashboardRepository;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.account.AccountAdapter;
import com.cdcplatform.service.account.AccountCapabilities;
import com.cdcplatform.service.dev.DevNodeReader;
import com.cdcplatform.service.dev.DevTempTableStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据集的创建、刷新、预览与删除。Excel 的解析与物化在 {@link DatasetExcelImporter}。 */
@Service
public class DatasetService {

    public record Detail(Dataset dataset, List<DatasetColumn> columns, Map<String, Object> file,
                         Map<String, Object> sheet) {
    }

    public record Preview(List<String> columns, List<List<Object>> rows, boolean truncated, long elapsedMs) {
    }

    private final DatasetRepository datasets;
    private final ConfigRepository configs;
    private final AccountAdapter adapter;
    private final DevNodeReader reader;
    private final DevTempTableStore datasetStore;
    private final DatasetProperties props;
    private final BiDashboardRepository biDashboards;

    public DatasetService(DatasetRepository datasets, ConfigRepository configs, AccountAdapter adapter,
                          DevNodeReader reader, @Qualifier("datasetTableStore") DevTempTableStore datasetStore,
                          DatasetProperties props, BiDashboardRepository biDashboards) {
        this.datasets = datasets;
        this.configs = configs;
        this.adapter = adapter;
        this.reader = reader;
        this.datasetStore = datasetStore;
        this.props = props;
        this.biDashboards = biDashboards;
    }

    public List<Dataset> list(String q, String type) {
        return datasets.datasets(q, type);
    }

    public Detail detail(long id) {
        return new Detail(datasets.dataset(id), datasets.columns(id), datasets.file(id), datasets.sheet(id));
    }

    public long createTable(long accountId, String database, String table, String name, String description, String user) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("请选择数据表");
        }
        var source = source(accountId);
        List<DatasetColumn> columns = new ArrayList<>();
        int ordinal = 1;
        for (var column : reader.describeTable(source, database, table)) {
            columns.add(new DatasetColumn(ordinal++, column.label(), column.h2Type(), true));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("该表没有可用字段");
        }
        Dataset dataset = new Dataset(null, name, description, "TABLE", accountId, database, table, null,
                null, null, 0, columns.size(), "READY", user, null, null);
        return datasets.create(dataset, columns);
    }

    public long createSql(long accountId, String database, String sql, String name, String description, String user) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("请填写查询 SQL");
        }
        var source = source(accountId);
        List<DatasetColumn> columns = new ArrayList<>();
        int ordinal = 1;
        for (var column : reader.describeSql(source, database, sql)) {
            columns.add(new DatasetColumn(ordinal++, column.label(), column.h2Type(), true));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("SQL 没有返回任何字段");
        }
        Dataset dataset = new Dataset(null, name, description, "SQL", accountId, database, null, sql,
                null, null, 0, columns.size(), "READY", user, null, null);
        return datasets.create(dataset, columns);
    }

    /** 改名/描述随时可改；改数据源要先重新描述字段，被数据流引用时还要显式确认。 */
    @org.springframework.transaction.annotation.Transactional
    public void update(long id, String name, String description, String tableName, String sqlText, boolean rebind) {
        Dataset dataset = datasets.dataset(id);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写数据集名称");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 160) {
            throw new IllegalArgumentException("数据集名称不能超过 160 个字符");
        }
        datasets.updateMeta(id, trimmed, description == null ? "" : description);

        boolean offline = "EXCEL".equals(dataset.datasetType()) || "EXTRACT".equals(dataset.datasetType());
        boolean wantsSource = (tableName != null && !tableName.isBlank()) || (sqlText != null && !sqlText.isBlank());
        if (offline) {
            if (wantsSource) {
                throw new IllegalArgumentException("离线数据集不支持修改数据源，请通过写入它的数据流重新生成");
            }
            return;
        }

        boolean tableType = "TABLE".equals(dataset.datasetType());
        if (tableType && (tableName == null || tableName.isBlank())) {
            throw new IllegalArgumentException("请填写数据表");
        }
        if (!tableType && (sqlText == null || sqlText.isBlank())) {
            throw new IllegalArgumentException("请填写查询 SQL");
        }
        boolean changed = tableType
                ? tableName != null && !tableName.isBlank() && !tableName.equals(dataset.tableName())
                : sqlText != null && !sqlText.isBlank() && !sqlText.equals(dataset.sqlText());
        if (!changed) {
            return;
        }
        if (!rebind && datasets.referencedByFlow(id)) {
            String names = datasets.referencingFlows(id).stream()
                    .map(row -> String.valueOf(row.get("name")))
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new IllegalStateException("数据集被数据流引用（" + names + "），修改数据源需要确认重新绑定");
        }

        var source = source(dataset.accountId());
        List<DatasetColumn> columns = new ArrayList<>();
        int ordinal = 1;
        var described = tableType
                ? reader.describeTable(source, dataset.databaseName(), tableName)
                : reader.describeSql(source, dataset.databaseName(), sqlText);
        for (var column : described) {
            columns.add(new DatasetColumn(ordinal++, column.label(), column.h2Type(), true));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("新数据源没有可用字段");
        }
        datasets.updateSource(id, tableType ? tableName : null, tableType ? null : sqlText);
        datasets.saveColumns(id, columns);
    }

    public long createExtract(String name, String description, String user) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写数据集名称");
        }
        long id = datasets.nextId();
        String storageTable = props.getStorageSchema() + ".DS_" + id + "_0";
        Dataset dataset = new Dataset(id, name.trim(), description == null ? "" : description, "EXTRACT",
                null, null, null, null, props.getStorageSchema(), storageTable, 0, 0, "READY", user, null, null);
        return datasets.create(dataset, List.of());
    }

    public void refresh(long id) {        Dataset dataset = datasets.dataset(id);
        if ("EXCEL".equals(dataset.datasetType()) || "EXTRACT".equals(dataset.datasetType())) {
            return;
        }
        var source = source(dataset.accountId());
        List<DatasetColumn> columns = new ArrayList<>();
        int ordinal = 1;
        var described = "SQL".equals(dataset.datasetType())
                ? reader.describeSql(source, dataset.databaseName(), dataset.sqlText())
                : reader.describeTable(source, dataset.databaseName(), dataset.tableName());
        for (var column : described) {
            columns.add(new DatasetColumn(ordinal++, column.label(), column.h2Type(), true));
        }
        datasets.saveColumns(id, columns);
    }

    public Preview preview(long id, int limit) {
        long started = System.currentTimeMillis();
        Dataset dataset = datasets.dataset(id);
        int safe = Math.max(1, Math.min(limit, 500));
        if ("EXCEL".equals(dataset.datasetType()) || "EXTRACT".equals(dataset.datasetType())) {
            if (dataset.storageTable() == null) {
                throw new IllegalArgumentException("数据集还没有物化数据");
            }
            try {
                var rows = datasetStore.preview(dataset.storageTable(), safe);
                return new Preview(rows.columns(), rows.rows(), rows.truncated(),
                        System.currentTimeMillis() - started);
            } catch (Exception e) {
                throw new IllegalArgumentException("离线数据集还没有数据，请先运行写入它的数据流");
            }
        }
        var source = source(dataset.accountId());
        var data = "SQL".equals(dataset.datasetType())
                ? reader.previewSql(source, dataset.databaseName(), dataset.sqlText(), safe)
                : reader.previewTable(source, dataset.databaseName(), dataset.tableName(), safe);
        return new Preview(data.columns(), data.rows(), data.truncated(), System.currentTimeMillis() - started);
    }

    public void delete(long id) {
        Dataset dataset = datasets.dataset(id);
        if (datasets.referencedByFlow(id)) {
            throw new IllegalStateException("数据集被数据流引用，请先解除引用");
        }
        // 看板卡片只靠外键 SET NULL 的话，图表会静默变空，比报错难排查得多
        long dashboards = biDashboards.referencingDashboards(id);
        if (dashboards > 0) {
            throw new IllegalStateException("数据集被 " + dashboards + " 个看板引用，请先移除相关卡片");
        }
        if (dataset.storageTable() != null) {
            datasetStore.drop(dataset.storageTable());
        }
        Map<String, Object> file = datasets.file(id);
        if (file != null && file.get("storagePath") != null) {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(String.valueOf(file.get("storagePath"))));
            } catch (Exception ignored) {
                // 文件删不掉不影响元数据删除，下次启动由清理任务处理
            }
        }
        datasets.delete(id);
    }

    /** 数据集读取必须能查询；Kafka 这类没有查询能力的账户直接拒绝。 */
    public com.cdcplatform.model.SourceConfig source(long accountId) {
        Account account = configs.account(accountId, true);
        AccountCapabilities.require(account.accountType(), Capability.QUERY, "创建或预览数据集");
        return adapter.toSource(account);
    }

    public String storageTable(long datasetId, int sheetIndex) {
        return props.getStorageSchema() + ".DS_" + datasetId + "_" + sheetIndex;
    }

    public Map<String, Object> summary(Dataset dataset) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", dataset.id());
        row.put("name", dataset.name());
        row.put("description", dataset.description());
        row.put("datasetType", dataset.datasetType());
        row.put("accountId", dataset.accountId());
        row.put("databaseName", dataset.databaseName());
        row.put("tableName", dataset.tableName());
        row.put("rowCount", dataset.rowCount());
        row.put("columnCount", dataset.columnCount());
        row.put("status", dataset.status());
        row.put("updatedAt", dataset.updatedAt());
        return row;
    }
}
