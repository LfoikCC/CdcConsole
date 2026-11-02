package com.cdcplatform.repository;

import com.cdcplatform.model.dataset.Dataset;
import com.cdcplatform.model.dataset.DatasetColumn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DatasetRepository {

    private final JdbcClient jdbc;

    public DatasetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Dataset> datasets(String q, String type) {
        var sql = new StringBuilder("SELECT * FROM cdc_dataset WHERE 1=1");
        if (q != null && !q.isBlank()) {
            sql.append(" AND LOWER(name) LIKE :like");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND dataset_type = :type");
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        var spec = jdbc.sql(sql.toString());
        if (q != null && !q.isBlank()) {
            spec = spec.param("like", "%" + q.trim().toLowerCase() + "%");
        }
        if (type != null && !type.isBlank()) {
            spec = spec.param("type", type.toUpperCase());
        }
        return spec.query((rs, n) -> read(rs)).list();
    }

    public Dataset dataset(long id) {
        return jdbc.sql("SELECT * FROM cdc_dataset WHERE id=:id").param("id", id)
                .query((rs, n) -> read(rs)).optional()
                .orElseThrow(() -> new IllegalArgumentException("数据集 " + id + " 不存在"));
    }

    public List<DatasetColumn> columns(long id) {
        return jdbc.sql("SELECT * FROM cdc_dataset_column WHERE dataset_id=:id ORDER BY ordinal")
                .param("id", id)
                .query((rs, n) -> new DatasetColumn(rs.getInt("ordinal"), rs.getString("name"),
                        rs.getString("data_type"), rs.getBoolean("nullable")))
                .list();
    }

    public Map<String, Object> file(long id) {
        return jdbc.sql("SELECT * FROM cdc_dataset_file WHERE dataset_id=:id").param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fileName", rs.getString("file_name"));
                    row.put("sizeBytes", rs.getLong("size_bytes"));
                    row.put("storagePath", rs.getString("storage_path"));
                    return row;
                }).optional().orElse(null);
    }

    public Map<String, Object> sheet(long id) {
        return jdbc.sql("SELECT * FROM cdc_dataset_sheet WHERE dataset_id=:id").param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sheetIndex", rs.getInt("sheet_index"));
                    row.put("sheetName", rs.getString("sheet_name"));
                    row.put("headerRow", rs.getInt("header_row"));
                    row.put("dataStartRow", rs.getInt("data_start_row"));
                    row.put("rowCount", rs.getLong("row_count"));
                    row.put("storageTable", rs.getString("storage_table"));
                    return row;
                }).optional().orElse(null);
    }

    public long create(Dataset dataset, List<DatasetColumn> columns) {
        long id = dataset.id() == null ? nextId() : dataset.id();
        jdbc.sql("INSERT INTO cdc_dataset(id,name,description,dataset_type,account_id,database_name,table_name," +
                        "sql_text,storage_schema,storage_table,row_count,column_count,status,created_by,created_at,updated_at) " +
                        "VALUES(:id,:name,:desc,:type,:account,:db,:table,:sql,:schema,:storage,:rows,:cols,:status,:by,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("id", id).param("name", dataset.name()).param("desc", dataset.description())
                .param("type", dataset.datasetType()).param("account", dataset.accountId())
                .param("db", dataset.databaseName()).param("table", dataset.tableName())
                .param("sql", dataset.sqlText()).param("schema", dataset.storageSchema())
                .param("storage", dataset.storageTable()).param("rows", dataset.rowCount())
                .param("cols", dataset.columnCount()).param("status", dataset.status())
                .param("by", dataset.createdBy()).update();
        saveColumns(id, columns);
        return id;
    }

    public void saveColumns(long datasetId, List<DatasetColumn> columns) {
        jdbc.sql("DELETE FROM cdc_dataset_column WHERE dataset_id=:id").param("id", datasetId).update();
        for (DatasetColumn column : columns) {
            jdbc.sql("INSERT INTO cdc_dataset_column(dataset_id,ordinal,name,data_type,nullable) " +
                            "VALUES(:id,:ordinal,:name,:type,:nullable)")
                    .param("id", datasetId).param("ordinal", column.ordinal()).param("name", column.name())
                    .param("type", column.dataType()).param("nullable", column.nullable()).update();
        }
        jdbc.sql("UPDATE cdc_dataset SET column_count=:count, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("count", columns.size()).param("id", datasetId).update();
    }

    public void updateMeta(long id, String name, String description) {
        jdbc.sql("UPDATE cdc_dataset SET name=:name, description=:desc, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("name", name).param("desc", description).update();
    }

    /** 只改数据源指向，不动 Excel 的物化表。 */
    public void updateSource(long id, String tableName, String sqlText) {
        jdbc.sql("UPDATE cdc_dataset SET table_name=:table, sql_text=:sql, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("table", tableName).param("sql", sqlText).update();
    }

    /** 引用该数据集的数据流，用于改源前的提示。 */
    public List<Map<String, Object>> referencingFlows(long id) {
        return jdbc.sql("SELECT id, name FROM dev_flow WHERE graph_json LIKE :needle ORDER BY id")
                .param("needle", "%\"datasetId\":" + id + "%").query().listOfRows();
    }

    public void updateRowCount(long id, long rows) {
        jdbc.sql("UPDATE cdc_dataset SET row_count=:rows, status='READY', updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", id).param("rows", rows).update();
    }

    public void saveFile(long datasetId, String fileName, long size, String sha256, String path) {
        jdbc.sql("DELETE FROM cdc_dataset_file WHERE dataset_id=:id").param("id", datasetId).update();
        jdbc.sql("INSERT INTO cdc_dataset_file(dataset_id,file_name,size_bytes,sha256,storage_path) " +
                        "VALUES(:id,:name,:size,:sha,:path)")
                .param("id", datasetId).param("name", fileName).param("size", size)
                .param("sha", sha256).param("path", path).update();
    }

    public void saveSheet(long datasetId, int sheetIndex, String sheetName, int headerRow, int dataStartRow,
                          long rowCount, String storageTable) {
        jdbc.sql("DELETE FROM cdc_dataset_sheet WHERE dataset_id=:id").param("id", datasetId).update();
        jdbc.sql("INSERT INTO cdc_dataset_sheet(dataset_id,sheet_index,sheet_name,header_row,data_start_row,row_count,storage_table) " +
                        "VALUES(:id,:index,:name,:header,:start,:rows,:table)")
                .param("id", datasetId).param("index", sheetIndex).param("name", sheetName)
                .param("header", headerRow).param("start", dataStartRow).param("rows", rowCount)
                .param("table", storageTable).update();
    }

    public void delete(long id) {
        dataset(id);
        jdbc.sql("DELETE FROM cdc_dataset WHERE id=:id").param("id", id).update();
    }

    /** 数据流里引用该数据集时不允许删除。 */
    public boolean referencedByFlow(long id) {
        long count = jdbc.sql("SELECT COUNT(*) FROM dev_flow WHERE graph_json LIKE :needle")
                .param("needle", "%\"datasetId\":" + id + "%").query(Long.class).single();
        return count > 0;
    }

    public long nextId() {
        return jdbc.sql("SELECT NEXT VALUE FOR cdc_config_id").query(Long.class).single();
    }

    private void saveColumnsIfAny(long datasetId, List<DatasetColumn> columns) {
        if (!columns.isEmpty()) {
            saveColumns(datasetId, columns);
        }
    }


    private Dataset read(ResultSet rs) throws SQLException {
        return new Dataset(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("dataset_type"), (Long) rs.getObject("account_id", Long.class),
                rs.getString("database_name"), rs.getString("table_name"), rs.getString("sql_text"),
                rs.getString("storage_schema"), rs.getString("storage_table"),
                rs.getLong("row_count"), rs.getInt("column_count"), rs.getString("status"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
