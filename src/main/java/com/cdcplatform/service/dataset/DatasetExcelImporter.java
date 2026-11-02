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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Excel 数据集：两阶段导入。
 *
 * <p>先解析出 sheet / 表头 / 类型让用户确认，再物化。这样用户能改表头行和列类型，
 * 而不是由系统猜一个「差不多」的结果。
 */
@Service
public class DatasetExcelImporter {

    public record ColumnSpec(String name, String type) {
    }

    public record SheetInfo(int index, String name, int rowCount, int headerRow,
                            List<ColumnSpec> columns, List<List<String>> sampleRows) {
    }

    public record ParseResult(String token, String fileName, long sizeBytes, List<SheetInfo> sheets) {
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
            throw new IllegalArgumentException("请选择 Excel 文件");
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
            throw new IllegalStateException("解析 Excel 失败：" + e.getMessage(), e);
        }
    }

    public long importSheet(String name, String description, String token, String fileName,
                            int sheetIndex, int headerRow, List<ColumnSpec> columns, String user) {
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
            int header = Math.max(1, Math.min(headerRow, Math.max(1, sheet.getLastRowNum() + 1)));
            List<ColumnMeta> metas = new ArrayList<>();
            List<DatasetColumn> metasForDb = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < columns.size(); i++) {
                ColumnSpec column = columns.get(i);
                String label = column.name() == null || column.name().isBlank() ? "列" + (i + 1) : column.name().trim();
                if (!names.add(label)) {
                    throw new IllegalArgumentException("字段名重复：" + label);
                }
                String type = column.type() == null ? "VARCHAR" : column.type().toUpperCase(Locale.ROOT);
                if (!TYPES.contains(type)) {
                    throw new IllegalArgumentException("不支持的字段类型：" + column.type());
                }
                metas.add(new ColumnMeta(i + 1, label, type));
                metasForDb.add(new DatasetColumn(i + 1, label, type, true));
            }

            datasetStore.drop(storageTable);
            datasetStore.create(storageTable, metas);
            long rows = datasetStore.insertRows(storageTable, metas,
                    rowIterable(sheet, header, metas), props.getMaxRows());

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
            datasets.saveSheet(datasetId, sheetIndex, sheet.getSheetName(), header, header + 1, rows, storageTable);
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
            log.error("导入 Excel 失败", e);
            throw new IllegalStateException("导入 Excel 失败：" + e.getMessage(), e);
        } finally {
            // 必须等 Workbook 关闭后再删：文件句柄还开着时 macOS 会报 Operation not permitted
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // 删不掉就留给下次清理，不影响导入结果
            }
        }
    }

    private SheetInfo inspect(int index, Sheet sheet) {
        int rowCount = Math.max(0, sheet.getLastRowNum());
        if (rowCount > props.getMaxRows()) {
            throw new IllegalArgumentException("sheet「" + sheet.getSheetName() + "」超过 " + props.getMaxRows() + " 行");
        }
        Row headerRow = sheet.getRow(0);
        int width = headerRow == null ? 0 : headerRow.getLastCellNum();
        if (width > props.getMaxColumns()) {
            throw new IllegalArgumentException("sheet「" + sheet.getSheetName() + "」超过 " + props.getMaxColumns() + " 列");
        }
        List<ColumnSpec> columns = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int c = 0; c < width; c++) {
            String label = headerRow == null ? "" : formatter.formatCellValue(headerRow.getCell(c)).trim();
            if (label.isEmpty()) {
                label = "列" + (c + 1);
            }
            String unique = label;
            int suffix = 2;
            while (!names.add(unique)) {
                unique = label + "_" + suffix++;
            }
            columns.add(new ColumnSpec(unique, inferType(sheet, c, 1)));
        }
        List<List<String>> sample = new ArrayList<>();
        for (int r = 1; r <= Math.min(SAMPLE_ROWS, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            List<String> values = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                values.add(row == null ? "" : formatter.formatCellValue(row.getCell(c)));
            }
            sample.add(values);
        }
        return new SheetInfo(index, sheet.getSheetName(), rowCount, 1, columns, sample);
    }

    private String inferType(Sheet sheet, int column, int dataStart) {
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

    private Iterable<List<Object>> rowIterable(Sheet sheet, int dataStart, List<ColumnMeta> columns) {
        int last = sheet.getLastRowNum();
        return () -> new Iterator<>() {
            private int index = dataStart;

            @Override
            public boolean hasNext() {
                return index <= last;
            }

            @Override
            public List<Object> next() {
                Row row = sheet.getRow(index++);
                List<Object> values = new ArrayList<>(columns.size());
                for (ColumnMeta column : columns) {
                    Cell cell = row == null ? null : row.getCell(column.index() - 1);
                    values.add(value(cell, column.h2Type(), index));
                }
                return values;
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
