package com.aiagent.rag.xlsx;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an .xlsx workbook (Apache POI) into structured per-sheet data —
 * header row + data rows — instead of a flat text dump, so downstream
 * chunking/embedding can preserve which column each value belongs to.
 *
 * Sheets with no data (no non-empty row at all) are skipped; a sheet whose
 * first non-empty row has a blank header cell falls back to "Column_N" for
 * that cell rather than dropping the column.
 */
@Slf4j
public class XlsxDocumentReader {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public List<XlsxSheetData> readSheets(InputStream inputStream) throws IOException {
        List<XlsxSheetData> result = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                XlsxSheetData sheetData = readSheet(sheet, evaluator);
                if (sheetData != null) {
                    result.add(sheetData);
                }
            }
        }
        return result;
    }

    private XlsxSheetData readSheet(Sheet sheet, FormulaEvaluator evaluator) {
        int headerRowIdx = findFirstNonEmptyRow(sheet);
        if (headerRowIdx < 0) {
            log.info("[XLSX-SHEET] sheet={} rows=0 columns=0 (empty sheet, skipped)", sheet.getSheetName());
            return null;
        }

        Row headerRow = sheet.getRow(headerRowIdx);
        int columnCount = Math.max(headerRow.getLastCellNum(), 0);

        List<String> columns = new ArrayList<>();
        for (int c = 0; c < columnCount; c++) {
            String header = getCellValueAsString(headerRow.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), evaluator);
            columns.add(header.isBlank() ? ("Column_" + (c + 1)) : header);
        }

        List<List<String>> rows = new ArrayList<>();
        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                values.add(getCellValueAsString(row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), evaluator));
            }
            rows.add(values);
        }

        log.info("[XLSX-SHEET] sheet={} rows={} columns={}", sheet.getSheetName(), rows.size(), columns.size());
        return new XlsxSheetData(sheet.getSheetName(), columns, rows);
    }

    private int findFirstNonEmptyRow(Sheet sheet) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && !isRowEmpty(row)) {
                return r;
            }
        }
        return -1;
    }

    private boolean isRowEmpty(Row row) {
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private String getCellValueAsString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                CellValue evaluated = evaluator.evaluate(cell);
                switch (evaluated.getCellType()) {
                    case STRING:
                        return evaluated.getStringValue().trim();
                    case BOOLEAN:
                        return String.valueOf(evaluated.getBooleanValue());
                    case NUMERIC:
                        return formatNumeric(evaluated.getNumberValue());
                    default:
                        return "";
                }
            } catch (Exception e) {
                // Không để một formula lỗi (VD tham chiếu vòng, hàm không hỗ trợ)
                // làm chết cả pipeline ingest — fallback về text công thức gốc.
                log.warn("[XLSX-CELL] Formula evaluation failed at {}, falling back to formula text", cell.getAddress());
                return cell.getCellFormula();
            }
        }

        switch (type) {
            case STRING:
                return cell.getStringCellValue().trim();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return DATE_FORMATTER.format(cell.getLocalDateTimeCellValue());
                }
                return formatNumeric(cell.getNumericCellValue());
            case BLANK:
            default:
                return "";
        }
    }

    private String formatNumeric(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
