package com.cdcplatform;

import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.dataset.DatasetExcelImporter;
import com.cdcplatform.service.dataset.DatasetService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 本地文件数据集：表头行、字段别名、去重主键、追加与替换。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataset-excel-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-dataset-excel-cache",
        "cdc.dataset.directory=target/test-dataset-excel-store"
})
class DatasetExcelTest {

    @Autowired
    DatasetExcelImporter importer;
    @Autowired
    DatasetService datasets;
    @Autowired
    DatasetRepository repository;

    /** 前两行是标题和表头，数据从第 3 行开始。 */
    private MockMultipartFile workbook(String name, List<String[]> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("手工数据");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("发货开票计划");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("客户编码");
            header.createCell(1).setCellValue("客户名称");
            header.createCell(2).setCellValue("金额");
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i + 2);
                String[] values = rows.get(i);
                for (int c = 0; c < values.length; c++) {
                    row.createCell(c).setCellValue(values[c]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", name, "application/vnd.ms-excel", out.toByteArray());
        }
    }

    @Test
    void importsWithHeaderRowAliasAndDedupeKeyThenAppends() throws Exception {
        var parsed = importer.parse(workbook("sales.xlsx", List.of(
                new String[]{"1000", "甲公司", "120"},
                new String[]{"1001", "乙公司", "80"},
                new String[]{"1002", "丙公司", "60"})));
        assertThat(parsed.sheets()).hasSize(1);

        // 表头在第 2 行：字段应该按这一行推导，原始名保留文件表头
        var inspected = importer.inspect(parsed.token(), 0, 2, null, null);
        assertThat(inspected.headerRow()).isEqualTo(2);
        assertThat(inspected.columns()).extracting(DatasetExcelImporter.ColumnSpec::name)
                .containsExactly("客户编码", "客户名称", "金额");
        assertThat(inspected.rowCount()).isEqualTo(3);

        List<DatasetExcelImporter.ColumnSpec> specs = inspected.columns().stream()
                .map(column -> new DatasetExcelImporter.ColumnSpec(
                        "金额".equals(column.name()) ? "开票金额" : column.name(), "VARCHAR", column.originalName()))
                .toList();
        long id = importer.importSheet("发货开票计划", "手工录入", parsed.token(), "sales.xlsx",
                0, 2, null, null, specs, "客户编码", "admin");

        var columns = repository.columns(id);
        assertThat(columns).extracting(column -> column.name() + "/" + column.originalName())
                .containsExactly("客户编码/客户编码", "客户名称/客户名称", "开票金额/金额");
        assertThat(repository.dataset(id).rowCount()).isEqualTo(3);
        assertThat(repository.sheet(id).get("dedupeKey")).isEqualTo("客户编码");

        var firstPage = datasets.rows(id, 0, 2);
        assertThat(firstPage.paged()).isTrue();
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.rows()).hasSize(2);
        assertThat(firstPage.hasMore()).isTrue();

        // 追加：1001 已存在（去重主键命中），只有 1003 会被写进去
        var appended = importer.parse(workbook("more.xlsx", List.of(
                new String[]{"1001", "乙公司", "80"},
                new String[]{"1003", "丁公司", "40"})));
        long added = importer.append(id, appended.token(), 0, 2, null, null);
        assertThat(added).isEqualTo(1);
        assertThat(datasets.rows(id, 0, 50).total()).isEqualTo(4);

        // 替换：清空后只留新文件的两行
        var replaced = importer.parse(workbook("final.xlsx", List.of(
                new String[]{"2000", "戊公司", "10"},
                new String[]{"2001", "己公司", "20"})));
        long count = importer.replace(id, replaced.token(), "final.xlsx", 0, 2, null, null);
        assertThat(count).isEqualTo(2);
        assertThat(datasets.rows(id, 0, 50).total()).isEqualTo(2);
        assertThat(repository.file(id).get("fileName")).isEqualTo("final.xlsx");

        datasets.delete(id);
    }
}
