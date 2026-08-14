package com.aiagent.rag.xlsx;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link XlsxChunker} — verifies every chunk keeps its
 * SHEET/COLUMNS header (so no chunk loses table context), that rows are
 * never split mid-row, and that each row gets its own chunk (so a query
 * about one specific row isn't diluted by unrelated neighboring rows).
 */
class XlsxChunkerTest {

    private XlsxSheetData sheetWithRows(String name, List<String> columns, int rowCount) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(List.of("V" + i + "-c1", "V" + i + "-c2"));
        }
        return new XlsxSheetData(name, columns, rows);
    }

    @Test
    void chunk_multiRowSheet_oneRowPerChunk() {
        XlsxSheetData sheet = sheetWithRows("Employees", List.of("ID", "Name"), 2);

        List<Document> chunks = XlsxChunker.chunk(List.of(sheet));

        // One row per chunk: a query about a single employee must not compete
        // against other employees' data inside the same embedding vector.
        assertEquals(2, chunks.size());
        for (Document chunk : chunks) {
            String content = chunk.getContent();
            assertTrue(content.contains("SHEET: Employees"));
            assertTrue(content.contains("COLUMNS:"));
            assertTrue(content.contains("ID | Name"));
            assertEquals(1, content.split("ROW:", -1).length - 1, "chunk must contain exactly one row");
            assertEquals("Employees", chunk.getMetadata().get("sheet_name"));
        }
        assertTrue(chunks.get(0).getContent().contains("V0-c1"));
        assertTrue(chunks.get(1).getContent().contains("V1-c1"));
    }

    @Test
    void chunk_rowValuesMetadata_containsOnlyThisRowsCells() {
        XlsxSheetData sheet = new XlsxSheetData("Employees", List.of("MaNhanVien", "HoTen"),
                List.of(List.of("NV0001", "Nguyen Van 1"), List.of("NV0002", "Nguyen Van 2")));

        List<Document> chunks = XlsxChunker.chunk(List.of(sheet));

        assertEquals(2, chunks.size());
        // Stored lower-cased for case-insensitive matching (see MetadataFilterBuilder).
        String[] row0Values = (String[]) chunks.get(0).getMetadata().get("row_values");
        List<String> row0List = Arrays.asList(row0Values);
        assertTrue(row0List.contains("nv0001"));
        assertTrue(row0List.contains("nguyen van 1"));
        assertFalse(row0List.contains("nv0002"), "chunk 0 must not leak chunk 1's values");

        String[] row1Values = (String[]) chunks.get(1).getMetadata().get("row_values");
        List<String> row1List = Arrays.asList(row1Values);
        assertTrue(row1List.contains("nv0002"));
        assertTrue(row1List.contains("nguyen van 2"));
    }

    @Test
    void chunk_rowValuesMetadata_diacriticsStripped() {
        // Real-world data is often stored WITHOUT Vietnamese diacritics (e.g.
        // exported from a system that only handles ASCII), but users type
        // questions WITH diacritics — row_values must normalize both the same
        // way (see NormalizationUtils.normalizeForMatching) so they still match.
        XlsxSheetData sheet = new XlsxSheetData("Employees", List.of("HoTen"),
                List.of(List.of("Nguyen Van 11")));

        List<Document> chunks = XlsxChunker.chunk(List.of(sheet));

        String[] values = (String[]) chunks.get(0).getMetadata().get("row_values");
        assertEquals("nguyen van 11", values[0]);
    }

    @Test
    void chunk_emptyCellsExcludedFromRowValues() {
        XlsxSheetData sheet = new XlsxSheetData("Employees", List.of("ID", "Note"),
                List.of(List.of("NV0001", "")));

        List<Document> chunks = XlsxChunker.chunk(List.of(sheet));

        String[] values = (String[]) chunks.get(0).getMetadata().get("row_values");
        assertEquals(1, values.length, "blank cell must not become a spurious row_values entry");
        assertEquals("nv0001", values[0]);
    }

    @Test
    void chunk_largeSheet_everyChunkRepeatsHeader() {
        XlsxSheetData sheet = sheetWithRows("BigSheet", List.of("Col1", "Col2"), 500);

        List<Document> chunks = XlsxChunker.chunk(List.of(sheet));

        assertEquals(500, chunks.size(), "one chunk per row");
        for (Document chunk : chunks) {
            String content = chunk.getContent();
            assertTrue(content.contains("SHEET: BigSheet"), "chunk missing SHEET header: " + content.substring(0, 50));
            assertTrue(content.contains("COLUMNS:"), "chunk missing COLUMNS header");
            assertTrue(content.contains("Col1 | Col2"), "chunk missing column names");
            assertEquals("BigSheet", chunk.getMetadata().get("sheet_name"));
        }
    }

    @Test
    void chunk_multipleSheets_eachChunkTaggedWithOwnSheet() {
        XlsxSheetData employees = sheetWithRows("Employees", List.of("ID", "Name"), 1);
        XlsxSheetData departments = sheetWithRows("Departments", List.of("Code", "Name"), 1);

        List<Document> chunks = XlsxChunker.chunk(List.of(employees, departments));

        assertEquals(2, chunks.size());
        assertEquals("Employees", chunks.get(0).getMetadata().get("sheet_name"));
        assertEquals("Departments", chunks.get(1).getMetadata().get("sheet_name"));
        assertTrue(chunks.get(0).getContent().contains("SHEET: Employees"));
        assertTrue(chunks.get(1).getContent().contains("SHEET: Departments"));
    }

    @Test
    void chunk_sheetWithNoRows_stillEmitsHeaderChunk() {
        XlsxSheetData emptySheet = new XlsxSheetData("Empty", List.of("A", "B"), List.of());

        List<Document> chunks = XlsxChunker.chunk(List.of(emptySheet));

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("SHEET: Empty"));
    }

    @Test
    void chunk_rowsNeverSplitMidRow() {
        XlsxSheetData sheet = sheetWithRows("Rows", List.of("A", "B"), 500);

        List<Document> chunks = XlsxChunker.chunk(List.of(sheet));

        int totalRowMarkers = 0;
        for (Document chunk : chunks) {
            String content = chunk.getContent();
            int count = content.split("ROW:", -1).length - 1;
            totalRowMarkers += count;
            // Every "A:" line inside a chunk must be followed by a "B:" line —
            // i.e. no row was cut in half across chunk boundaries.
            long aCount = content.lines().filter(l -> l.startsWith("A: ")).count();
            long bCount = content.lines().filter(l -> l.startsWith("B: ")).count();
            assertEquals(aCount, bCount, "row split mid-way inside a single chunk");
        }
        assertEquals(500, totalRowMarkers);
    }
}
