package com.aiagent.rag.xlsx;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link XlsxDocumentReader} — pure POI round-trip, no Spring
 * context, no Qdrant/embedding model needed.
 */
class XlsxDocumentReaderTest {

    private InputStream buildEmployeesWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Ma nhan vien");
            header.createCell(1).setCellValue("Ho ten");
            header.createCell(2).setCellValue("Phong ban");
            header.createCell(3).setCellValue("Luong");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("NV001");
            row1.createCell(1).setCellValue("Nguyen Van A");
            row1.createCell(2).setCellValue("IT");
            row1.createCell(3).setCellValue(15000000);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("NV002");
            row2.createCell(1).setCellValue("Tran Van B");
            row2.createCell(2).setCellValue("HR");
            row2.createCell(3).setCellValue(12000000);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return new ByteArrayInputStream(bos.toByteArray());
        }
    }

    @Test
    void readSheets_singleSheet_readsHeaderAndRows() throws IOException {
        List<XlsxSheetData> sheets = new XlsxDocumentReader().readSheets(buildEmployeesWorkbook());

        assertEquals(1, sheets.size());
        XlsxSheetData sheet = sheets.get(0);
        assertEquals("Employees", sheet.getSheetName());
        assertEquals(List.of("Ma nhan vien", "Ho ten", "Phong ban", "Luong"), sheet.getColumns());
        assertEquals(2, sheet.getRows().size());
        assertEquals(List.of("NV001", "Nguyen Van A", "IT", "15000000"), sheet.getRows().get(0));
        assertEquals(List.of("NV002", "Tran Van B", "HR", "12000000"), sheet.getRows().get(1));
    }

    @Test
    void readSheets_multipleSheets_noneAreDropped() throws IOException {
        InputStream in;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet employees = workbook.createSheet("Employees");
            Row h1 = employees.createRow(0);
            h1.createCell(0).setCellValue("ID");
            Row r1 = employees.createRow(1);
            r1.createCell(0).setCellValue("E1");

            Sheet departments = workbook.createSheet("Departments");
            Row h2 = departments.createRow(0);
            h2.createCell(0).setCellValue("Code");
            Row r2 = departments.createRow(1);
            r2.createCell(0).setCellValue("IT");

            Sheet projects = workbook.createSheet("Projects");
            Row h3 = projects.createRow(0);
            h3.createCell(0).setCellValue("Name");
            Row r3 = projects.createRow(1);
            r3.createCell(0).setCellValue("Project X");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            in = new ByteArrayInputStream(bos.toByteArray());
        }

        List<XlsxSheetData> sheets = new XlsxDocumentReader().readSheets(in);

        assertEquals(3, sheets.size());
        assertEquals("Employees", sheets.get(0).getSheetName());
        assertEquals("Departments", sheets.get(1).getSheetName());
        assertEquals("Projects", sheets.get(2).getSheetName());
    }

    @Test
    void readSheets_allDataTypes_areExtractedWithoutLoss() throws IOException {
        InputStream in;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Types");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("StringCol");
            header.createCell(1).setCellValue("NumberCol");
            header.createCell(2).setCellValue("BooleanCol");
            header.createCell(3).setCellValue("DateCol");
            header.createCell(4).setCellValue("FormulaCol");
            header.createCell(5).setCellValue("EmptyCol");

            CreationHelper createHelper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("dd/mm/yyyy"));

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Hello");
            row.createCell(1).setCellValue(42.0);
            row.createCell(2).setCellValue(true);
            Cell dateCell = row.createCell(3);
            dateCell.setCellValue(new Date(124, 0, 15)); // 15/01/2024
            dateCell.setCellStyle(dateStyle);
            row.createCell(4).setCellFormula("1+2");
            row.createCell(5); // empty cell, no value set

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            in = new ByteArrayInputStream(bos.toByteArray());
        }

        List<XlsxSheetData> sheets = new XlsxDocumentReader().readSheets(in);
        assertEquals(1, sheets.size());
        List<String> values = sheets.get(0).getRows().get(0);

        assertEquals("Hello", values.get(0));
        assertEquals("42", values.get(1));
        assertEquals("true", values.get(2));
        assertEquals("15/01/2024", values.get(3));
        assertEquals("3", values.get(4)); // formula 1+2 evaluated
        assertEquals("", values.get(5)); // empty cell preserved as empty, not dropped
    }

    @Test
    void readSheets_emptySheet_isSkippedNotCrashed() throws IOException {
        InputStream in;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("EmptySheet");

            Sheet withData = workbook.createSheet("WithData");
            Row header = withData.createRow(0);
            header.createCell(0).setCellValue("Col");
            Row row = withData.createRow(1);
            row.createCell(0).setCellValue("Val");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            in = new ByteArrayInputStream(bos.toByteArray());
        }

        List<XlsxSheetData> sheets = new XlsxDocumentReader().readSheets(in);

        assertEquals(1, sheets.size());
        assertEquals("WithData", sheets.get(0).getSheetName());
    }

    @Test
    void readSheets_blankHeaderCell_fallsBackToColumnName() throws IOException {
        InputStream in;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1); // blank header cell, no name
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1");
            row.createCell(1).setCellValue("mystery value");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            in = new ByteArrayInputStream(bos.toByteArray());
        }

        List<XlsxSheetData> sheets = new XlsxDocumentReader().readSheets(in);
        List<String> columns = sheets.get(0).getColumns();

        assertEquals("ID", columns.get(0));
        assertTrue(columns.get(1).startsWith("Column_"));
    }
}
