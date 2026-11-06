package com.cdcplatform.service.dataset;

import com.cdcplatform.config.DatasetProperties;
import com.cdcplatform.model.dataset.Dataset;
import com.cdcplatform.model.dataset.DatasetColumn;
import com.cdcplatform.repository.DatasetRepository;
import com.cdcplatform.service.dev.DevTempTableStore;
import com.cdcplatform.service.dev.DevTempTableStore.ColumnMeta;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件数据集：两阶段导入。
 *
 * <p>先解析出 sheet / 表头 / 类型让用户确认，再物化。这样用户能改表头行、列范围和列类型，
 * 而不是由系统猜一个「差不多」的结果。
 */
@Service
public class DatasetExcelImporter {

    /** {@code name} 是展示别名，{@code originalName} 是文件里的表头，追加数据靠它匹配。 */
    public record ColumnSpec(String name, String type, String originalName) {
        public ColumnSpec(String name, String type) {
            this(name, type, name);
        }
    }

    public record SheetInfo(int index, String name, int rowCount, int headerRow,
                            List<ColumnSpec> columns, List<List<String>> sampleRows) {
    }

    public record ParseResult(String token, String fileName, long sizeBytes, List<SheetInfo> sheets) {
    }

    /** 换表头行 / 列范围后重新推导出来的配置。 */
    public record InspectResult(int sheetIndex, int headerRow, int startColumn, int endColumn, int rowCount,
                                List<ColumnSpec> columns, List<List<String>> sampleRows) {
    }

    private static final Set<String> TYPES = Set.of(
            "BIGINT", "DECIMAL(38,10)", "DOUBLE", "VARCHAR", "TIMESTAMP", "BOOLEAN");
    private static final Logger log = LoggerFactory.getLogger(DatasetExcelImporter.class);
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int SAMPLE_ROWS = 5;
    private static final int INFER_ROWS = 200;

    private final DatasetRepository datasets;
    private final DevTempTableStore datasetStore;
    private final DatasetProperties props;
    private final DataFormatter formatter = new DataFormatter();

    public DatasetExcelImporter(DatasetRepository datasets,
                                @Qualifier("datasetTableStore") DevTempTableStore datasetStore,
                                DatasetProperties props) {
        this.datasets = datasets;
        this.datasetStore = datasetStore;
        this.props = props;
    }

    public ParseResult parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 20MB 上限");
        }
        String original = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        String lower = original.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IllegalArgumentException("只支持 .xlsx 或 .xls 文件");
        }
        String token = UUID.randomUUID().toString();
        try {
            Path tmp = tmpDir().resolve(token + (lower.endsWith(".xlsx") ? ".xlsx" : ".xls"));
            Files.createDirectories(tmp.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try (Workbook workbook = WorkbookFactory.create(tmp.toFile())) {
                if (workbook.getNumberOfSheets() > props.getMaxSheets()) {
                    throw new IllegalArgumentException("文件包含超过 " + props.getMaxSheets() + " 个 sheet");
                }
                List<SheetInfo> sheets = new ArrayList<>();
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    sheets.add(inspect(i, workbook.getSheetAt(i)));
                }
                return new ParseResult(token, original, file.getSize(), sheets);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析文件失败：" + e.getMessage(), e);
        }
    }

    /** 用户改了 sheet / 表头行 / 列范围之后重新推导字段与样例。 */
    public InspectResult inspect(String token, int sheetIndex, int headerRow, Integer startColumn, Integer endColumn) {
        Path tmp = findTmp(token);
        try (Workbook workbook = WorkbookFactory.create(tmp.toFile())) {
            if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                throw new IllegalArgumentException("sheet 不存在");
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int header = clampHeader(sheet, headerRow);
            int lastColumn = lastColumn(sheet, header);
            int from = startColumn == null ? 0 : Math.max(0, startColumn);
            int to = endColumn == null ? lastColumn : Math.min(Math.max(from, endColumn), lastColumn);
            if (to <= from) {
                throw new IllegalArgumentException("选取的列范围为空");
            }
            List<ColumnSpec> columns = headerColumns(sheet, header, from, to);
            List<List<String>> sample = sampleRows(sheet, header, from, to);
            int rows = Math.max(0, sheet.getLastRowNum() - header + 1);
            return new InspectResult(sheetIndex, header, from, to, rows, columns, sample);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取文件失败：" + e.getMessage(), e);
        }
    }

    public long importSheet(String name, String description, String token, String fileName,
                            int sheetIndex, int headerRow, Integer startColumn, Integer endColumn,
                            List<ColumnSpec> columns, String dedupeKey, String user) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写数据集名称");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("请至少保留一个字段");
        }
        Path tmp = findTmp(token);
        long datasetId = datasets.nextId();
        String storageTable = props.getStorageSchema() + ".DS_" + datasetId + "_" + sheetIndex;
        try (Workbook workbook = WorkbookFactory.create(tmp.toFile())) {
            if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
                throw new IllegalArgumentException("sheet 不存在");
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int header = clampHeader(sheet, headerRow);
            int last = lastColumn(sheet, header);
            int from = startColumn == null ? 0 : Math.max(0, startColumn);
            int to = endColumn == null ? last : Math.min(Math.max(from, endColumn), last);
            if (to <= from) {
                throw new IllegalArgumentException("选取的列范围为空");
            }

            List<ColumnMeta> metas = new ArrayList<>();
            List<DatasetColumn> metasForDb = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < columns.size(); i++) {
                ColumnSpec column = columns.get(i);
                String label = column.name() == null || column.name().isBlank() ? "列" + (i + 1) : column.name().trim();
                if (!names.add(label)) {
                    throw new IllegalArgumentException("字段名重复：" + label);
                }
                String type = normalizeType(column.type());
                String original = column.originalName() == null || column.originalName().isBlank()
                        ? label : column.originalName().trim();
                metas.add(new ColumnMeta(i + 1, label, type));
                metasForDb.add(new DatasetColumn(i + 1, label, type, true, original));
            }

            datasetStore.drop(storageTable);
            datasetStore.create(storageTable, metas);
            long rows = datasetStore.insertRows(storageTable, metas,
                    rowIterable(sheet, header, from, metas, null, -1), props.getMaxRows());

            String sha256 = sha256(tmp);
            Path finalPath = props.directoryPath().resolve(String.valueOf(datasetId));
            Files.createDirectories(finalPath);
            Path stored = finalPath.resolve(token + extension(tmp));
            Files.copy(tmp, stored, StandardCopyOption.REPLACE_EXISTING);

            Dataset dataset = new Dataset(datasetId, name.trim(), description, "EXCEL", null, null, null, null,
                    props.getStorageSchema(), storageTable, rows, metasForDb.size(), "READY", user, null, null);
            datasets.create(dataset, metasForDb);
            datasets.saveFile(datasetId, fileName == null || fileName.isBlank() ? tmp.getFileName().toString() : fileName,
                    Files.size(stored), sha256, stored.toString());
            datasets.saveSheet(datasetId, sheetIndex, sheet.getSheetName(), header, header + 1, rows, storageTable,
                    normalizeDedupe(dedupeKey, columns));
            return datasetId;
        } catch (Exception e) {
            datasetStore.drop(storageTable);
            try {
                datasets.delete(datasetId);
            } catch (Exception ignored) {
                // 记录可能还没写入
            }
            if (e instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            log.error("导入本地文件失败", e);
            throw new IllegalStateException("导入本地文件失败：" + e.getMessage(), e);
        } finally {
            // 必须等 Workbook 关闭后再删：文件句柄还开着时 macOS 会报 Operation not permitted
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // 删不掉就留给下次清理，不影响导入结果
            }
        }
    }

    /**
     * 追加数据：按表头名和现有字段（原始字段名优先，其次别名）对齐。
     *
     * <p>表里没有的列忽略，数据集里有但文件里缺的列留空；配了去重主键时跳过已存在的键值。
     */
    public long append(long datasetId, String token, int sheetIndex, int headerRow,
                       Integer startColumn, Integer endColumn) {
        Dataset dataset = requireLocal(datasetId);
        List<DatasetColumn> columns = datasets.columns(datasetId);
        Map<String, Object> sheetMeta = datasets.sheet(datasetId);
        String dedupeKey = sheetMeta == null ? null : (String) sheetMeta.get("dedupeKey");
        long existingRows = datasetStore.rowCount(dataset.storageTable());
        Path tmp = findTmp(token);
        try (Workbook workbook = WorkbookFactory.create(tmp.toFile())) {
            Sheet sheet = sheetOfWorkbook(workbook, sheetIndex);
            int header = clampHeader(sheet, headerRow);
            int from = startColumn == null ? 0 : Math.max(0, startColumn);
            int to = endColumn == null ? lastColumn(sheet, header) : endColumn;
            int[] mapping = mapColumns(sheet, header, from, to, columns);
            List<ColumnMeta> metas = metasOf(columns);
            int keyIndex = indexOf(columns, dedupeKey);
            Set<String> seen = keyIndex < 0 ? null : datasetStore.valuesOf(dataset.storageTable(), metas.get(keyIndex));
            long added = datasetStore.insertRows(dataset.storageTable(), metas,
                    rowIterable(sheet, header, from, metas, mapping, keyIndex == -1 ? -1 : keyIndex, seen),
                    props.getMaxRows(), existingRows);
            datasets.updateRowCount(datasetId, existingRows + added);
            return added;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("追加数据失败：" + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** 替换数据：字段结构不变，先清空再按同一套字段映射写入。 */
    public long replace(long datasetId, String token, String fileName, int sheetIndex, int headerRow,
                        Integer startColumn, Integer endColumn) {
        Dataset dataset = requireLocal(datasetId);
        List<DatasetColumn> columns = datasets.columns(datasetId);
        Map<String, Object> sheetMeta = datasets.sheet(datasetId);
        String dedupeKey = sheetMeta == null ? null : (String) sheetMeta.get("dedupeKey");
        Path tmp = findTmp(token);
        try (Workbook workbook = WorkbookFactory.create(tmp.toFile())) {
            Sheet sheet = sheetOfWorkbook(workbook, sheetIndex);
            int header = clampHeader(sheet, headerRow);
            int from = startColumn == null ? 0 : Math.max(0, startColumn);
            int to = endColumn == null ? lastColumn(sheet, header) : endColumn;
            int[] mapping = mapColumns(sheet, header, from, to, columns);
            List<ColumnMeta> metas = metasOf(columns);
            int keyIndex = indexOf(columns, dedupeKey);
            Set<String> seen = keyIndex < 0 ? null : new HashSet<>();
            datasetStore.execute("DELETE FROM " + dataset.storageTable());
            long rows = datasetStore.insertRows(dataset.storageTable(), metas,
                    rowIterable(sheet, header, from, metas, mapping, keyIndex == -1 ? -1 : keyIndex, seen),
                    props.getMaxRows(), 0);
            datasets.updateRowCount(datasetId, rows);
            datasets.saveSheet(datasetId, sheetIndex, sheet.getSheetName(), header, header + 1, rows,
                    dataset.storageTable(), dedupeKey);
            replaceStoredFile(datasetId, tmp, fileName);
            return rows;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("替换数据失败：" + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private Dataset requireLocal(long datasetId) {
        Dataset dataset = datasets.dataset(datasetId);
        if (!"EXCEL".equals(dataset.datasetType())) {
            throw new IllegalArgumentException("只有本地文件数据集支持追加或替换数据");
        }
        if (dataset.storageTable() == null) {
            throw new IllegalStateException("数据集还没有物化数据");
        }
        return dataset;
    }

    /** 替换后把文件也换成新的，日期与大小才和「最近数据更新时间」对得上。 */
    private void replaceStoredFile(long datasetId, Path tmp, String fileName) throws Exception {
        String sha256 = sha256(tmp);
        Path finalPath = props.directoryPath().resolve(String.valueOf(datasetId));
        Files.createDirectories(finalPath);
        Path stored = finalPath.resolve(UUID.randomUUID() + extension(tmp));
        Files.copy(tmp, stored, StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> previous = datasets.file(datasetId);
        datasets.saveFile(datasetId, fileName == null || fileName.isBlank()
                        ? tmp.getFileName().toString() : fileName,
                Files.size(stored), sha256, stored.toString());
        if (previous != null && previous.get("storagePath") != null) {
            try {
                Files.deleteIfExists(Path.of(String.valueOf(previous.get("storagePath"))));
            } catch (Exception ignored) {
                // 旧文件删不掉不影响数据
            }
        }
    }

    private Sheet sheetOfWorkbook(Workbook workbook, int sheetIndex) {
        if (sheetIndex < 0 || sheetIndex >= workbook.getNumberOfSheets()) {
            throw new IllegalArgumentException("sheet 不存在");
        }
        return workbook.getSheetAt(sheetIndex);
    }

    private SheetInfo inspect(int index, Sheet sheet) {
        int rowCount = Math.max(0, sheet.getLastRowNum());
        if (rowCount > props.getMaxRows()) {
            throw new IllegalArgumentException("sheet「" + sheet.getSheetName() + "」超过 " + props.getMaxRows() + " 行");
        }
        int headerRow = detectHeaderRow(sheet);
        int width = lastColumn(sheet, headerRow);
        if (width > props.getMaxColumns()) {
            throw new IllegalArgumentException("sheet「" + sheet.getSheetName() + "」超过 " + props.getMaxColumns() + " 列");
        }
        List<ColumnSpec> columns = headerColumns(sheet, headerRow, 0, width);
        List<List<String>> sample = sampleRows(sheet, headerRow, 0, width);
        return new SheetInfo(index, sheet.getSheetName(), rowCount, headerRow, columns, sample);
    }

    /**
     * 猜表头行：前 10 行里第一个有 2 个以上非空单元格的行。
     *
     * <p>导出文件常在表头上面放一行标题，默认按第 1 行读会只剩一个字段，用户得自己改。
     */
    private int detectHeaderRow(Sheet sheet) {
        int last = Math.min(sheet.getLastRowNum(), 9);
        for (int r = 0; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int filled = 0;
            for (Cell cell : row) {
                if (!formatter.formatCellValue(cell).trim().isEmpty()) {
                    filled++;
                }
            }
            if (filled >= 2) {
                return r + 1;
            }
        }
        return 1;
    }

    /** 表头行（1 起）→ 列范围里的字段，空表头补「列N」，重名加后缀。 */
    private List<ColumnSpec> headerColumns(Sheet sheet, int headerRow, int from, int to) {
        Row header = sheet.getRow(headerRow - 1);
        List<ColumnSpec> columns = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int c = from; c < to; c++) {
            String label = header == null ? "" : formatter.formatCellValue(header.getCell(c)).trim();
            if (label.isEmpty()) {
                label = "列" + (c + 1);
            }
            String unique = label;
            int suffix = 2;
            while (!names.add(unique)) {
                unique = label + "_" + suffix++;
            }
            columns.add(new ColumnSpec(unique, inferType(sheet, c, headerRow), label));
        }
        return columns;
    }

    private List<List<String>> sampleRows(Sheet sheet, int headerRow, int from, int to) {
        List<List<String>> sample = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = headerRow; r <= Math.min(lastRow, headerRow + SAMPLE_ROWS - 1); r++) {
            Row row = sheet.getRow(r);
            List<String> values = new ArrayList<>();
            for (int c = from; c < to; c++) {
                values.add(row == null ? "" : formatter.formatCellValue(row.getCell(c)));
            }
            sample.add(values);
        }
        return sample;
    }

    private int clampHeader(Sheet sheet, int headerRow) {
        return Math.max(1, Math.min(headerRow, Math.max(1, sheet.getLastRowNum() + 1)));
    }

    /** 表头行最后一个非空列（0 起，返回的是上界）。 */
    private int lastColumn(Sheet sheet, int headerRow) {
        Row header = sheet.getRow(headerRow - 1);
        int width = header == null ? 0 : header.getLastCellNum();
        if (width <= 0) {
            throw new IllegalArgumentException("表头行没有内容，请换一行");
        }
        return width;
    }

    /** 文件表头 → 现场字段位置；对不上的列留 -1，写入时按空值处理。 */
    private int[] mapColumns(Sheet sheet, int headerRow, int from, int to, List<DatasetColumn> columns) {
        Row header = sheet.getRow(headerRow - 1);
        Map<String, Integer> byLabel = new HashMap<>();
        for (int c = from; c < to; c++) {
            String label = header == null ? "" : formatter.formatCellValue(header.getCell(c));
            String key = normalizeLabel(label);
            if (!key.isEmpty()) {
                byLabel.putIfAbsent(key, c);
            }
        }
        int[] mapping = new int[columns.size()];
        int matched = 0;
        for (int i = 0; i < columns.size(); i++) {
            DatasetColumn column = columns.get(i);
            Integer found = byLabel.get(normalizeLabel(column.originalName()));
            if (found == null) {
                found = byLabel.get(normalizeLabel(column.name()));
            }
            mapping[i] = found == null ? -1 : found;
            if (found != null) {
                matched++;
            }
        }
        if (matched == 0) {
            throw new IllegalArgumentException("文件表头和数据集字段对不上，请调整表头行或列范围");
        }
        return mapping;
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static List<ColumnMeta> metasOf(List<DatasetColumn> columns) {
        List<ColumnMeta> metas = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            DatasetColumn column = columns.get(i);
            metas.add(new ColumnMeta(i + 1, column.name(), column.dataType()));
        }
        return metas;
    }

    private static int indexOf(List<DatasetColumn> columns, String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            DatasetColumn column = columns.get(i);
            if (dedupeKey.equals(column.name()) || dedupeKey.equals(column.originalName())) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeType(String type) {
        String value = type == null ? "VARCHAR" : type.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(value)) {
            throw new IllegalArgumentException("不支持的字段类型：" + type);
        }
        return value;
    }

    /** 去重主键存字段别名；指向不存在的字段就当没配。 */
    private static String normalizeDedupe(String dedupeKey, List<ColumnSpec> columns) {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return null;
        }
        return columns.stream()
                .map(ColumnSpec::name)
                .filter(name -> name != null && name.equals(dedupeKey))
                .findFirst()
                .orElse(null);
    }

    private String inferType(Sheet sheet, int column, int headerRow) {
        int dataStart = headerRow; // 表头行 1 起，数据从下一行（0 起索引就是 headerRow）开始
        boolean any = false, allLong = true, allNumber = true, allBool = true, allDate = true;
        int last = Math.min(sheet.getLastRowNum(), dataStart + INFER_ROWS);
        for (int r = dataStart; r <= last; r++) {
            Row row = sheet.getRow(r);
            Cell cell = row == null ? null : row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                continue;
            }
            any = true;
            CellType type = cell.getCellType() == CellType.FORMULA
                    ? cell.getCachedFormulaResultType() : cell.getCellType();
            if (type == CellType.NUMERIC) {
                allBool = false;
                if (DateUtil.isCellDateFormatted(cell)) {
                    allNumber = false;
                    allLong = false;
                } else {
                    allDate = false;
                    double value = cell.getNumericCellValue();
                    if (value != Math.rint(value) || Math.abs(value) > 9.0E18) {
                        allLong = false;
                    }
                }
            } else if (type == CellType.BOOLEAN) {
                allNumber = false;
                allLong = false;
                allDate = false;
            } else {
                allNumber = false;
                allLong = false;
                allDate = false;
                String text = formatter.formatCellValue(cell).trim();
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)
                        && !"0".equals(text) && !"1".equals(text)) {
                    allBool = false;
                }
            }
        }
        if (!any) {
            return "VARCHAR";
        }
        if (allBool) {
            return "BOOLEAN";
        }
        if (allNumber) {
            return allLong ? "BIGINT" : "DECIMAL(38,10)";
        }
        if (allDate) {
            return "TIMESTAMP";
        }
        return "VARCHAR";
    }

    private Iterable<List<Object>> rowIterable(Sheet sheet, int headerRow, int from,
                                               List<ColumnMeta> columns, int[] mapping, int keyIndex) {
        return rowIterable(sheet, headerRow, from, columns, mapping, keyIndex, null);
    }

    /**
     * 逐行读成 {@code columns} 顺序的值。
     *
     * <p>{@code mapping} 为空表示按顺序取（新建导入）；{@code seen} 非空时按去重主键跳过重复行。
     */
    private Iterable<List<Object>> rowIterable(Sheet sheet, int headerRow, int from,
                                               List<ColumnMeta> columns, int[] mapping, int keyIndex,
                                               Set<String> seen) {
        int last = sheet.getLastRowNum();
        return () -> new Iterator<>() {
            private int index = headerRow;
            private List<Object> next;
            private boolean ready;

            private void advance() {
                while (index <= last) {
                    Row row = sheet.getRow(index++);
                    List<Object> values = new ArrayList<>(columns.size());
                    for (int i = 0; i < columns.size(); i++) {
                        int column = mapping == null ? from + i : mapping[i];
                        Cell cell = column < 0 || row == null ? null : row.getCell(column);
                        values.add(value(cell, columns.get(i).h2Type(), index));
                    }
                    if (seen != null && keyIndex >= 0) {
                        Object key = values.get(keyIndex);
                        if (key == null || !seen.add(String.valueOf(key))) {
                            continue;
                        }
                    }
                    next = values;
                    ready = true;
                    return;
                }
                ready = false;
            }

            @Override
            public boolean hasNext() {
                if (!ready) {
                    advance();
                }
                return ready;
            }

            @Override
            public List<Object> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return next;
            }
        };
    }

    private Object value(Cell cell, String type, int rowNumber) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        try {
            if ("BIGINT".equals(type)) {
                if (cell.getCellType() == CellType.NUMERIC) {
                    return (long) cell.getNumericCellValue();
                }
                String text = formatter.formatCellValue(cell).trim();
                return text.isEmpty() ? null : Long.parseLong(text);
            }
            if (type.startsWith("DECIMAL") || "DOUBLE".equals(type)) {
                if (cell.getCellType() == CellType.NUMERIC) {
                    return BigDecimal.valueOf(cell.getNumericCellValue());
                }
                String text = formatter.formatCellValue(cell).trim();
                return text.isEmpty() ? null : new BigDecimal(text);
            }
            if ("TIMESTAMP".equals(type)) {
                if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                    return new Timestamp(cell.getDateCellValue().getTime());
                }
                String text = formatter.formatCellValue(cell).trim();
                return text.isEmpty() ? null : parseTimestamp(text);
            }
            if ("BOOLEAN".equals(type)) {
                if (cell.getCellType() == CellType.BOOLEAN) {
                    return cell.getBooleanCellValue();
                }
                String text = formatter.formatCellValue(cell).trim();
                return text.isEmpty() ? null : "true".equalsIgnoreCase(text) || "1".equals(text);
            }
            String text = formatter.formatCellValue(cell).trim();
            return text.isEmpty() ? null : text;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("第 " + rowNumber + " 行的值无法转换为 " + type + "：" + e.getMessage());
        }
    }

    private static Timestamp parseTimestamp(String text) {
        String normalized = text.replace('/', '-').replace('T', ' ');
        try {
            return Timestamp.valueOf(normalized.length() == 10 ? normalized + " 00:00:00" : normalized);
        } catch (IllegalArgumentException e) {
            return Timestamp.valueOf(LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay());
        }
    }

    private Path findTmp(String token) {
        if (token == null || !token.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("上传凭证无效，请重新上传文件");
        }
        try (var stream = Files.list(tmpDir())) {
            return stream.filter(path -> path.getFileName().toString().startsWith(token))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("上传文件已过期，请重新上传"));
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
        }
    }

    private Path tmpDir() {
        return props.directoryPath().resolve("tmp");
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ".xlsx" : name.substring(dot);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 删不掉就留给下次清理
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
