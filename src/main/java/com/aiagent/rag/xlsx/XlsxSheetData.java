package com.aiagent.rag.xlsx;

import lombok.Getter;

import java.util.List;

/**
 * Structured content of a single XLSX sheet: header row (column names) plus
 * data rows, each row aligned positionally with {@link #getColumns()}.
 */
@Getter
public class XlsxSheetData {

    private final String sheetName;
    private final List<String> columns;
    private final List<List<String>> rows;

    public XlsxSheetData(String sheetName, List<String> columns, List<List<String>> rows) {
        this.sheetName = sheetName;
        this.columns = columns;
        this.rows = rows;
    }
}
