package com.aiagent.rag.xlsx;

import java.util.List;

/**
 * Renders {@link XlsxSheetData} into the structured "SHEET / COLUMNS / ROW"
 * text format so a row's values stay tagged with their column names instead
 * of being flattened into an ambiguous string of numbers/words.
 *
 * The document-level "DOCUMENT: &lt;title&gt;" prefix is intentionally NOT
 * added here — DocumentIngestionService.upsertChunks already prepends it
 * (plus PROJECT/DESCRIPTION) to every chunk regardless of file type, so
 * adding it here would duplicate it.
 */
public final class XlsxStructuredTextBuilder {

    private XlsxStructuredTextBuilder() {
    }

    /** Full text for every sheet/row — used for DB content storage and content-hash duplicate detection. */
    public static String buildFullText(List<XlsxSheetData> sheets) {
        StringBuilder sb = new StringBuilder();
        for (XlsxSheetData sheet : sheets) {
            sb.append(buildSheetHeader(sheet));
            for (List<String> row : sheet.getRows()) {
                sb.append(buildRowBlock(sheet.getColumns(), row));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    static String buildSheetHeader(XlsxSheetData sheet) {
        return "SHEET: " + sheet.getSheetName() + "\n\nCOLUMNS:\n"
                + String.join(" | ", sheet.getColumns()) + "\n\n";
    }

    static String buildRowBlock(List<String> columns, List<String> row) {
        StringBuilder sb = new StringBuilder("ROW:\n");
        for (int i = 0; i < columns.size(); i++) {
            String value = i < row.size() ? row.get(i) : "";
            sb.append(columns.get(i)).append(": ").append(value).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }
}
