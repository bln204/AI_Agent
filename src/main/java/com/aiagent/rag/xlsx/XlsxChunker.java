package com.aiagent.rag.xlsx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits XLSX sheet data into embedding-ready chunks that each repeat the
 * SHEET + COLUMNS header, so no chunk loses which sheet/columns its rows
 * belong to — unlike generic token-based splitting, which would keep the
 * header only in the first chunk.
 *
 * One data row per chunk by default: each row in a table (an employee, a
 * project, ...) is its own semantic entity, and batching many rows into one
 * chunk dilutes the embedding vector so a query about a single specific row
 * (e.g. "nhân viên NV025") ranks poorly against chunks that average dozens
 * of unrelated rows together. Keeping rows 1:1 with chunks is also what the
 * original XLSX ingestion spec's own example shows. MAX_CHUNK_CHARS is kept
 * only as a defensive cap for pathologically wide single rows — it does not
 * cause multiple rows to share a chunk.
 *
 * Rows never split across cell boundaries; a chunk always contains whole
 * rows. Sheet/document identity survives per-chunk via the repeated header
 * plus the "sheet_name" metadata attached to each chunk.
 */
@Slf4j
public final class XlsxChunker {

    // Rows per chunk. 1 keeps each row's embedding un-diluted by neighboring
    // rows so per-entity lookups (a specific employee/row) retrieve reliably.
    private static final int ROWS_PER_CHUNK = 1;

    // Defensive cap in case a single row (many/long columns) is unusually
    // large; not used to batch multiple rows together.
    private static final int MAX_CHUNK_CHARS = 3200;

    private XlsxChunker() {
    }

    public static List<Document> chunk(List<XlsxSheetData> sheets) {
        List<Document> chunks = new ArrayList<>();
        for (XlsxSheetData sheet : sheets) {
            chunks.addAll(chunkSheet(sheet));
        }
        return chunks;
    }

    private static List<Document> chunkSheet(XlsxSheetData sheet) {
        List<Document> result = new ArrayList<>();
        String header = XlsxStructuredTextBuilder.buildSheetHeader(sheet);

        if (sheet.getRows().isEmpty()) {
            result.add(newChunk(sheet, header, List.of()));
            log.info("[XLSX-CHUNK] sheet={} chunks={}", sheet.getSheetName(), result.size());
            return result;
        }

        StringBuilder buffer = new StringBuilder(header);
        List<List<String>> rowsInBuffer = new ArrayList<>();

        for (List<String> row : sheet.getRows()) {
            String rowBlock = XlsxStructuredTextBuilder.buildRowBlock(sheet.getColumns(), row);
            boolean rowLimitReached = rowsInBuffer.size() >= ROWS_PER_CHUNK;
            boolean charLimitReached = !rowsInBuffer.isEmpty() && buffer.length() + rowBlock.length() > MAX_CHUNK_CHARS;
            if (!rowsInBuffer.isEmpty() && (rowLimitReached || charLimitReached)) {
                result.add(newChunk(sheet, buffer.toString(), rowsInBuffer));
                buffer = new StringBuilder(header);
                rowsInBuffer = new ArrayList<>();
            }
            buffer.append(rowBlock);
            rowsInBuffer.add(row);
        }
        result.add(newChunk(sheet, buffer.toString(), rowsInBuffer));

        log.info("[XLSX-CHUNK] sheet={} chunks={}", sheet.getSheetName(), result.size());
        return result;
    }

    private static Document newChunk(XlsxSheetData sheet, String content, List<List<String>> rows) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sheet_name", sheet.getSheetName());

        // Literal cell values of this chunk's row(s) — lets retrieval do an
        // exact metadata match (e.g. an employee code/name typed in a
        // question) instead of relying only on semantic similarity, which
        // struggles to tell structurally-similar rows apart. See
        // EntityType.ROW_VALUE / MetadataFilterBuilder. Stored via
        // NormalizationUtils.normalizeForMatching (lowercase + diacritics
        // stripped) — MetadataFilterBuilder normalizes the query-side value
        // the same way, so "Nguyễn Văn 11" typed in a question still matches
        // a cell stored as "Nguyen Van 11" (no diacritics), and vice versa.
        Set<String> values = new LinkedHashSet<>();
        for (List<String> row : rows) {
            for (String v : row) {
                if (v != null && !v.isBlank()) {
                    values.add(com.aiagent.util.NormalizationUtils.normalizeForMatching(v));
                }
            }
        }
        if (!values.isEmpty()) {
            metadata.put("row_values", values.toArray(new String[0]));
        }

        return new Document(content.trim(), metadata);
    }
}
