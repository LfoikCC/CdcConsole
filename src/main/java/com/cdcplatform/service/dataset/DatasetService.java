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

    /** 数据集明细：{@code paged=false} 表示直连数据集，只能看前若干行。 */
    public record DataRows(List<Map<String, Object>> columns, List<List<Object>> rows, long total,
                           boolean hasMore, boolean paged) {
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

    /**
     * 改字段展示名。
     *
     * <p>只写平台里的别名，数据源的物理字段名（{@code original_name}）不动，
     * 所以刷新字段、追加数据都还按原名匹配。
     */
    @org.springframework.transaction.annotation.Transactional
    public void renameColumn(long id, int ordinal, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("字段名称不能为空");
        }
        if (trimmed.length() > 160) {
            throw new IllegalArgumentException("字段名称不能超过 160 个字符");
        }
        List<DatasetColumn> columns = datasets.columns(id);
        DatasetColumn target = columns.stream()
                .filter(column -> column.ordinal() == ordinal)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("字段不存在或已被刷新：" + ordinal));
        if (trimmed.equals(target.name())) {
            return;
        }
        boolean duplicated = columns.stream().anyMatch(column -> column.ordinal() != ordinal
                && column.name() != null && column.name().equalsIgnoreCase(trimmed));
        if (duplicated) {
            throw new IllegalArgumentException("字段名称「" + trimmed + "」已存在");
        }
        datasets.updateColumnName(id, ordinal, trimmed);
        Dataset dataset = datasets.dataset(id);
        applyColumnName(dataset, target, trimmed);
    }

    /**
     * 用数据源里的字段注释当展示名。
     *
     * <p>注释重复（多个字段都写「备用字段」）或与现有名字撞车时跳过，避免出现两个同名字段。
     *
     * @return 实际改名的字段数
     */
    @org.springframework.transaction.annotation.Transactional
    public int syncColumnComments(long id) {
        Dataset dataset = datasets.dataset(id);
        if (!"TABLE".equals(dataset.datasetType())) {
            throw new IllegalArgumentException("只有直连库表的数据集能读取字段注释");
        }
        Map<String, String> comments = reader.columnComments(source(dataset.accountId()),
                dataset.databaseName(), dataset.tableName());
        if (comments.isEmpty()) {
            return 0;
        }
        List<DatasetColumn> columns = datasets.columns(id);
        Map<Integer, String> planned = commentNames(columns, comments);
        for (DatasetColumn column : columns) {
            String name = planned.get(column.ordinal());
            if (name != null) {
                applyColumnName(dataset, column, name);
            }
        }
        return planned.size();
    }

    /** 全部还原成数据源里的原始字段名。 */
    @org.springframework.transaction.annotation.Transactional
    public int resetColumnNames(long id) {
        Dataset dataset = datasets.dataset(id);
        int changed = 0;
        for (DatasetColumn column : datasets.columns(id)) {
            String original = column.originalName();
            if (original == null || original.isBlank() || original.equals(column.name())) {
                continue;
            }
            applyColumnName(dataset, column, original);
            changed++;
        }
        return changed;
    }

    /** 注释 → 新字段名的计划：ordinal 到目标名字。只挑能安全改的。 */
    static Map<Integer, String> commentNames(List<DatasetColumn> columns, Map<String, String> comments) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String value : comments.values()) {
            String key = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.isEmpty()) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        Map<Integer, String> planned = new java.util.LinkedHashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (DatasetColumn column : columns) {
            String raw = comments.get(column.originalName());
            String candidate = raw == null ? "" : raw.trim();
            boolean usable = !candidate.isEmpty()
                    && counts.getOrDefault(candidate.toLowerCase(java.util.Locale.ROOT), 0) == 1
                    && !candidate.equals(column.name());
            if (usable) {
                planned.put(column.ordinal(), candidate);
            } else {
                used.add(String.valueOf(column.name()).toLowerCase(java.util.Locale.ROOT));
            }
        }
        // 注释和没改名的字段重名时也不能用
        planned.entrySet().removeIf(entry -> !used.add(entry.getValue().toLowerCase(java.util.Locale.ROOT)));
        return planned;
    }

    /** 改一个字段的展示名：库里的元数据、物化表列目录、卡片/筛选器引用一起同步。 */
    private void applyColumnName(Dataset dataset, DatasetColumn column, String name) {
        datasets.updateColumnName(dataset.id(), column.ordinal(), name);
        if (dataset.storageTable() != null) {
            // 物化表的物理列是 C1..Cn，显示名存在列目录里，跟着一起改
            datasetStore.renameColumn(dataset.storageTable(), column.ordinal(), name);
        }
        biDashboards.renameField(dataset.id(), column.name(), name);
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

    /** 详情页的数据明细：离线数据集按 ORD 分页，直连数据集也在数据源侧分页。 */
    public DataRows rows(long id, int offset, int limit) {
        Dataset dataset = datasets.dataset(id);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<DatasetColumn> columns = datasets.columns(id);
        boolean offline = "EXCEL".equals(dataset.datasetType()) || "EXTRACT".equals(dataset.datasetType());
        if (offline) {
            if (dataset.storageTable() == null) {
                throw new IllegalArgumentException("数据集还没有物化数据");
            }
            try {
                var page = datasetStore.page(dataset.storageTable(), safeOffset, safeLimit);
                long total = datasetStore.rowCount(dataset.storageTable());
                return new DataRows(columnMeta(columns, page.columns()), page.rows(), total,
                        safeOffset + page.rows().size() < total, true);
            } catch (RuntimeException e) {
                // 物化表还不存在时 H2 报的是「表/结构不存在」，对用户没有意义
                throw new IllegalArgumentException("离线数据集还没有数据，请先运行写入它的数据流", e);
            }
        }
        var source = source(dataset.accountId());
        boolean sqlType = "SQL".equals(dataset.datasetType());
        var page = sqlType
                ? reader.pageSql(source, dataset.databaseName(), dataset.sqlText(), safeOffset, safeLimit)
                : reader.pageTable(source, dataset.databaseName(), dataset.tableName(), safeOffset, safeLimit);
        long total = sqlType
                ? reader.countSql(source, dataset.databaseName(), dataset.sqlText())
                : reader.countTable(source, dataset.databaseName(), dataset.tableName());
        return new DataRows(columnMeta(columns, page.columns()), page.rows(), total,
                safeOffset + page.rows().size() < total, true);
    }

    /** 字段名 + 原始字段名 + 类型；元数据缺失或与数据源对不上时退回数据源里的列名。 */
    private static List<Map<String, Object>> columnMeta(List<DatasetColumn> columns, List<String> fallback) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (columns.isEmpty() || (!fallback.isEmpty() && columns.size() != fallback.size())) {
            for (String name : fallback) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("originalName", name);
                row.put("dataType", "");
                rows.add(row);
            }
            return rows;
        }
        for (DatasetColumn column : columns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", column.name());
            row.put("originalName", column.originalName());
            row.put("dataType", column.dataType());
            rows.add(row);
        }
        return rows;
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
        row.put("createdAt", dataset.createdAt());
        row.put("updatedAt", dataset.updatedAt());
        return row;
    }
}
