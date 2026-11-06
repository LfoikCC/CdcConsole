package com.cdcplatform.api;

import com.cdcplatform.model.PageResult;
import com.cdcplatform.service.dataset.DatasetExcelImporter;
import com.cdcplatform.service.dataset.DatasetService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    public record CreateInput(String name, String description, String type, Long accountId,
                              String databaseName, String tableName, String sqlText) {
    }

    public record ExcelInput(String name, String description, String token, String fileName,
                             Integer sheetIndex, Integer headerRow, Integer startColumn, Integer endColumn,
                             List<DatasetExcelImporter.ColumnSpec> columns, String dedupeKey) {
    }

    public record InspectInput(String token, Integer sheetIndex, Integer headerRow,
                               Integer startColumn, Integer endColumn) {
    }

    /** 追加/替换：文件先走 /excel/parse 拿 token，再带上 sheet 与表头配置提交。 */
    public record ReloadInput(String token, String fileName, Integer sheetIndex, Integer headerRow,
                              Integer startColumn, Integer endColumn) {
    }

    public record UpdateInput(String name, String description, String tableName, String sqlText, Boolean rebind) {
    }

    /** 字段改名：只改系统里的展示名，不影响数据源。 */
    public record ColumnInput(String name) {
    }

    public record ExtractInput(String name, String description) {
    }

    private final DatasetService datasets;
    private final DatasetExcelImporter importer;

    public DatasetController(DatasetService datasets, DatasetExcelImporter importer) {
        this.datasets = datasets;
        this.importer = importer;
    }

    @GetMapping
    Object list(@RequestParam(defaultValue = "") String q,
                @RequestParam(required = false) String type,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "50") int size) {
        List<Map<String, Object>> rows = datasets.list(q, type).stream()
                .map(datasets::summary).toList();
        return PageResult.of(rows, page, size);
    }

    @GetMapping("/{id}")
    Object detail(@PathVariable long id) {
        var detail = datasets.detail(id);
        Map<String, Object> body = new LinkedHashMap<>(datasets.summary(detail.dataset()));
        body.put("columns", detail.columns());
        body.put("file", detail.file());
        body.put("sheet", detail.sheet());
        body.put("sqlText", detail.dataset().sqlText());
        return body;
    }

    @PutMapping("/{id}")
    void update(@PathVariable long id, @RequestBody UpdateInput input) {
        datasets.update(id, input.name(), input.description(), input.tableName(), input.sqlText(),
                Boolean.TRUE.equals(input.rebind()));
    }

    @PutMapping("/{id}/columns/{ordinal}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void renameColumn(@PathVariable long id, @PathVariable int ordinal, @RequestBody ColumnInput input) {
        datasets.renameColumn(id, ordinal, input.name());
    }

    /** 用数据源里的字段注释批量替换展示名。 */
    @PostMapping("/{id}/columns/sync-comments")
    Object syncColumnComments(@PathVariable long id) {
        return Map.of("updated", datasets.syncColumnComments(id));
    }

    /** 展示名全部还原成原始字段名。 */
    @PostMapping("/{id}/columns/reset")
    Object resetColumnNames(@PathVariable long id) {
        return Map.of("updated", datasets.resetColumnNames(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@RequestBody CreateInput input, Authentication authentication) {
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException("请填写数据集名称");
        }
        String user = authentication == null ? null : authentication.getName();
        String type = input.type() == null ? "TABLE" : input.type().toUpperCase();
        long id = switch (type) {
            case "TABLE" -> datasets.createTable(require(input.accountId()), input.databaseName(),
                    input.tableName(), input.name(), input.description(), user);
            case "SQL" -> datasets.createSql(require(input.accountId()), input.databaseName(),
                    input.sqlText(), input.name(), input.description(), user);
            default -> throw new IllegalArgumentException("不支持的数据集类型：" + input.type());
        };
        return Map.of("id", id);
    }

    @PostMapping("/extract")
    @ResponseStatus(HttpStatus.CREATED)
    Object createExtract(@RequestBody ExtractInput input, Authentication authentication) {
        String user = authentication == null ? null : authentication.getName();
        return Map.of("id", datasets.createExtract(input.name(), input.description(), user));
    }

    @PostMapping("/excel/parse")
    Object parse(@RequestParam("file") MultipartFile file) {
        return importer.parse(file);
    }

    @PostMapping("/excel/inspect")
    Object inspect(@RequestBody InspectInput input) {
        return importer.inspect(input.token(), value(input.sheetIndex(), 0), value(input.headerRow(), 1),
                input.startColumn(), input.endColumn());
    }

    @PostMapping("/excel")
    @ResponseStatus(HttpStatus.CREATED)
    Object createFromExcel(@RequestBody ExcelInput input, Authentication authentication) {
        String user = authentication == null ? null : authentication.getName();
        long id = importer.importSheet(input.name(), input.description(), input.token(), input.fileName(),
                value(input.sheetIndex(), 0), value(input.headerRow(), 1), input.startColumn(), input.endColumn(),
                input.columns(), input.dedupeKey(), user);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/append")
    Object append(@PathVariable long id, @RequestBody ReloadInput input) {
        long added = importer.append(id, input.token(), value(input.sheetIndex(), 0),
                value(input.headerRow(), 1), input.startColumn(), input.endColumn());
        return Map.of("added", added);
    }

    @PostMapping("/{id}/replace")
    Object replace(@PathVariable long id, @RequestBody ReloadInput input) {
        long rows = importer.replace(id, input.token(), input.fileName(), value(input.sheetIndex(), 0),
                value(input.headerRow(), 1), input.startColumn(), input.endColumn());
        return Map.of("rows", rows);
    }

    @GetMapping("/{id}/rows")
    Object rows(@PathVariable long id, @RequestParam(defaultValue = "0") int offset,
                @RequestParam(defaultValue = "50") int limit) {
        return datasets.rows(id, offset, limit);
    }

    @PostMapping("/{id}/refresh")
    void refresh(@PathVariable long id) {
        datasets.refresh(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        datasets.delete(id);
    }

    private static long require(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("请选择数据账户");
        }
        return accountId;
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
