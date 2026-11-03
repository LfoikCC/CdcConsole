package com.cdcplatform.service.bi;

import com.cdcplatform.config.BiProperties;
import com.cdcplatform.model.Account;
import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.Types.Capability;
import com.cdcplatform.model.dataset.Dataset;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.ConnectionProbeService;
import com.cdcplatform.service.account.AccountAdapter;
import com.cdcplatform.service.account.AccountCapabilities;
import com.cdcplatform.service.bi.BiQueryBuilder.CardConfig;
import com.cdcplatform.service.bi.BiQueryBuilder.Column;
import com.cdcplatform.service.dev.DevTempTableStore;
import com.cdcplatform.service.sql.SqlIdentifiers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行图表聚合查询。
 *
 * <p>三种数据集走两条路径：直连数据集（TABLE / SQL）开一条只读的源库连接，
 * 离线数据集（EXCEL / EXTRACT）查应用自己的 H2 物化表。两边都限制超时与行数。
 *
 * <p>结果**按列序号读取**：H2 会把标识符折成大写、MySQL 保持原样，按名字取会踩大小写不一致；
 * 展示用列名由 {@code Plan.labels()} 另外给出。
 */
@Service
public class BiQueryService {

    public record Result(List<String> columns, List<List<Object>> rows, boolean truncated, long elapsedMs) {
    }

    private final BiQueryBuilder builder;
    private final DatasetRepository datasets;
    private final ConfigRepository configs;
    private final AccountAdapter adapter;
    private final ConnectionProbeService probe;
    private final DevTempTableStore datasetStore;
    private final DataSource dataSource;
    private final BiProperties props;

    public BiQueryService(BiQueryBuilder builder, DatasetRepository datasets, ConfigRepository configs,
                          AccountAdapter adapter, ConnectionProbeService probe,
                          @Qualifier("datasetTableStore") DevTempTableStore datasetStore,
                          DataSource dataSource, BiProperties props) {
        this.builder = builder;
        this.datasets = datasets;
        this.configs = configs;
        this.adapter = adapter;
        this.probe = probe;
        this.datasetStore = datasetStore;
        this.dataSource = dataSource;
        this.props = props;
    }

    /** 按数据集 + 图表配置取一次数。 */
    public Result query(long datasetId, CardConfig config) {
        long started = System.currentTimeMillis();
        Dataset dataset = datasets.dataset(datasetId);
        BiQueryBuilder.Source source = source(dataset);
        var plan = builder.build(source, config, props.getMaxRows());
        try (Connection connection = open(source, dataset)) {
            // 只读要在建 statement 之前设置：有些驱动一旦开始执行就不再接受
            connection.setReadOnly(!source.isStorage());
            try (PreparedStatement statement = connection.prepareStatement(plan.sql())) {
                statement.setQueryTimeout(props.getQueryTimeoutSeconds());
                statement.setFetchSize(200);
                statement.setMaxRows(plan.limit() + 1);
                for (int i = 0; i < plan.params().size(); i++) {
                    statement.setObject(i + 1, plan.params().get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    int width = plan.labels().size();
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= plan.limit()) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(width);
                        for (int i = 1; i <= width; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return new Result(plan.labels(), rows, truncated, System.currentTimeMillis() - started);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------- internals

    private Connection open(BiQueryBuilder.Source source, Dataset dataset) throws SQLException {
        if (source.isStorage()) {
            return dataSource.getConnection();
        }
        SourceConfig config = sourceConfig(dataset.accountId());
        return DriverManager.getConnection(
                SqlIdentifiers.withTimeouts(probe.streamingUrl(config, source.database()), config.dbType()),
                config.username(), config.password());
    }

    private BiQueryBuilder.Source source(Dataset dataset) {
        if ("EXCEL".equals(dataset.datasetType()) || "EXTRACT".equals(dataset.datasetType())) {
            if (dataset.storageTable() == null || dataset.storageTable().isBlank()) {
                throw new IllegalArgumentException("数据集「" + dataset.name() + "」还没有物化数据，请先运行写入它的数据流");
            }
            String full = dataset.storageTable();
            int dot = full.indexOf('.');
            String schema = dot > 0 ? full.substring(0, dot) : "DATASET_STORE";
            String table = dot > 0 ? full.substring(dot + 1) : full;
            List<Column> columns = new ArrayList<>();
            try {
                for (var meta : datasetStore.columnsOf(full)) {
                    // 物理列是 C1..Cn，真实列名只用于展示
                    columns.add(new Column(meta.label(), meta.physical()));
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("数据集「" + dataset.name() + "」还没有物化数据，请先运行写入它的数据流");
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("数据集「" + dataset.name() + "」还没有物化数据，请先运行写入它的数据流");
            }
            return BiQueryBuilder.Source.storage(schema, table, columns);
        }

        SourceConfig config = sourceConfig(dataset.accountId());
        String database = dataset.databaseName() == null || dataset.databaseName().isBlank()
                ? config.databaseName()
                : dataset.databaseName();
        List<Column> columns = new ArrayList<>();
        for (var column : datasets.columns(dataset.id())) {
            columns.add(new Column(column.name(), column.name()));
        }
        return "SQL".equals(dataset.datasetType())
                ? BiQueryBuilder.Source.sql(config.dbType(), database, dataset.sqlText(), columns)
                : BiQueryBuilder.Source.table(config.dbType(), database, dataset.tableName(), columns);
    }

    /** 数据集必须能查询；Kafka 这类没有查询能力的账户直接拒绝。 */
    private SourceConfig sourceConfig(long accountId) {
        Account account = configs.account(accountId, true);
        AccountCapabilities.require(account.accountType(), Capability.QUERY, "创建图表");
        return adapter.toSource(account);
    }
}
