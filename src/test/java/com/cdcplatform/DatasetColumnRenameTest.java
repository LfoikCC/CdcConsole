package com.cdcplatform;

import com.cdcplatform.model.bi.BiCard;
import com.cdcplatform.repository.BiDashboardRepository;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.dataset.DatasetExcelImporter;
import com.cdcplatform.service.dataset.DatasetService;
import com.cdcplatform.service.dev.DevTempTableStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字段改名：只改系统里的展示名，数据源字段、已存的卡片与筛选器跟着同步。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataset-column-rename-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-dataset-rename-cache",
        "cdc.dataset.directory=target/test-dataset-rename-store"
})
class DatasetColumnRenameTest {

    @Autowired
    DatasetExcelImporter importer;
    @Autowired
    DatasetService datasets;
    @Autowired
    DatasetRepository repository;
    @Autowired
    BiDashboardRepository dashboards;
    @Autowired
    @Qualifier("datasetTableStore")
    DevTempTableStore datasetStore;

    private MockMultipartFile workbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("手工数据");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("客户编码");
            header.createCell(1).setCellValue("客户名称");
            header.createCell(2).setCellValue("金额");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1000");
            row.createCell(1).setCellValue("甲公司");
            row.createCell(2).setCellValue("120");
            workbook.write(out);
            return new MockMultipartFile("file", "sales.xlsx", "application/vnd.ms-excel", out.toByteArray());
        }
    }

    private long importDataset() throws Exception {
        var parsed = importer.parse(workbook());
        var inspected = importer.inspect(parsed.token(), 0, 1, null, null);
        List<DatasetExcelImporter.ColumnSpec> specs = inspected.columns().stream()
                .map(column -> new DatasetExcelImporter.ColumnSpec(
                        "金额".equals(column.name()) ? "开票金额" : column.name(), "VARCHAR", column.originalName()))
                .toList();
        return importer.importSheet("发货开票计划", "", parsed.token(), "sales.xlsx",
                0, 1, null, null, specs, null, "admin");
    }

    @Test
    void renamesDisplayNameAndKeepsThePhysicalColumn() throws Exception {
        long id = importDataset();
        long dashboardId = dashboards.create("字段改名", "", "admin", null);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dimensions", List.of(Map.of("column", "开票金额")));
        config.put("metrics", List.of(Map.of("column", "开票金额", "agg", "SUM")));
        dashboards.replaceCards(dashboardId, List.of(
                new BiCard(null, "bar", "按客户", id, 0, 0, 6, 8, config)));
        dashboards.updateFilters(dashboardId, List.of(Map.of(
                "id", "f1", "type", "select", "datasetId", id, "column", "开票金额",
                "values", List.of("甲公司"))));

        datasets.renameColumn(id, 3, "金额（元）");

        // 展示名改了，原始字段名还是文件里的表头
        assertThat(repository.columns(id)).extracting(column -> column.name() + "/" + column.originalName())
                .containsExactly("客户编码/客户编码", "客户名称/客户名称", "金额（元）/金额");
        // 物化表的列目录同步，数据明细、下游节点看到的都是新名字
        String storage = repository.dataset(id).storageTable();
        assertThat(datasetStore.columnsOf(storage)).extracting(DevTempTableStore.ColumnMeta::label)
                .containsExactly("客户编码", "客户名称", "金额（元）");
        assertThat(datasets.rows(id, 0, 50).columns()).extracting(column -> column.get("name"))
                .containsExactly("客户编码", "客户名称", "金额（元）");

        // 卡片与筛选器里存的字段引用一起改，已做好的图表不会因为改名失效
        var card = dashboards.cards(dashboardId).get(0);
        assertThat(String.valueOf(((List<?>) card.config().get("dimensions")).get(0)))
                .contains("金额（元）").doesNotContain("开票金额");
        assertThat(dashboards.dashboard(dashboardId).filters())
                .extracting(filter -> filter.get("column")).containsExactly("金额（元）");

        dashboards.delete(dashboardId);
        datasets.delete(id);
    }

    @Test
    void resetsDisplayNamesBackToTheOriginalOnes() throws Exception {
        long id = importDataset();
        long dashboardId = dashboards.create("字段重置", "", "admin", null);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dimensions", List.of(Map.of("column", "开票金额")));
        dashboards.replaceCards(dashboardId, List.of(
                new BiCard(null, "bar", "按客户", id, 0, 0, 6, 8, config)));

        datasets.renameColumn(id, 3, "金额（元）");
        assertThat(dashboards.cards(dashboardId).get(0).config().get("dimensions").toString())
                .contains("金额（元）");

        assertThat(datasets.resetColumnNames(id)).isEqualTo(1);

        assertThat(repository.columns(id)).extracting(column -> column.name())
                .containsExactly("客户编码", "客户名称", "金额");
        assertThat(dashboards.cards(dashboardId).get(0).config().get("dimensions").toString())
                .contains("金额").doesNotContain("金额（元）");
        // 已经是原始字段名时不用再改
        assertThat(datasets.resetColumnNames(id)).isZero();

        dashboards.delete(dashboardId);
        datasets.delete(id);
    }

    @Test
    void commentSyncOnlyWorksForDirectTables() throws Exception {
        long id = importDataset();
        assertThatThrownBy(() -> datasets.syncColumnComments(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有直连库表");
        datasets.delete(id);
    }

    @Test
    void rejectsBlankDuplicateAndUnknownColumns() throws Exception {
        long id = importDataset();

        assertThatThrownBy(() -> datasets.renameColumn(id, 3, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> datasets.renameColumn(id, 3, "客户名称"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已存在");
        assertThatThrownBy(() -> datasets.renameColumn(id, 99, "随便"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("字段不存在");

        // 改成同名是空操作，不报错也不写库
        datasets.renameColumn(id, 3, "开票金额");
        assertThat(repository.columns(id)).extracting(column -> column.name()).contains("开票金额");

        datasets.delete(id);
    }
}
