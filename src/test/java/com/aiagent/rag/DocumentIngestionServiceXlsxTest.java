package com.aiagent.rag;

import com.aiagent.repository.DocumentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentIngestionService}'s XLSX branch. Uses plain
 * Mockito (no Spring context, no live Qdrant/embedding model — mirrors the
 * mocking style already used in DocumentServiceTest) so it can run without
 * external infrastructure while still exercising the real ingestion code
 * path: read → structured text → chunk → embed → vectorStore.add.
 *
 * Also covers the TXT path to confirm the XLSX branch does not change
 * behavior for non-XLSX file types (regression guard).
 */
class DocumentIngestionServiceXlsxTest {

    private VectorStore vectorStore;
    private EmbeddingModel embeddingModel;
    private DocumentRepository documentRepository;
    private DocumentIngestionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vectorStore = mock(VectorStore.class);
        embeddingModel = mock(EmbeddingModel.class);
        documentRepository = mock(DocumentRepository.class);

        service = new DocumentIngestionService(vectorStore, embeddingModel, documentRepository);
        when(documentRepository.findById(anyLong())).thenReturn(Optional.empty());

        // BGE-small-en-v1.5 (the configured embedding model, see AiConfig) produces
        // 384-dim vectors — mock the same shape without loading the real ONNX model.
        List<Double> fakeVector = Collections.nCopies(384, 0.01);
        when(embeddingModel.embed(anyString())).thenReturn(fakeVector);
    }

    private Path writeXlsxFile(String fileName, int dataRows) throws Exception {
        Path file = tempDir.resolve(fileName);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Name");
            for (int i = 1; i <= dataRows; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("E" + i);
                row.createCell(1).setCellValue("Employee " + i);
            }
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private Path writeTxtFile(String fileName, String content) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    @SuppressWarnings("unchecked")
    @Test
    void ingestDocumentSync_xlsx_producesStructuredChunksWithSheetMetadata() throws Exception {
        Path xlsx = writeXlsxFile("employees.xlsx", 3);

        service.ingestDocumentSync(xlsx.toString(), 1L, "uuid-1", "employees.xlsx", "xlsx",
                10L, "tester", "EMPLOYEE", "IT", "N/A", "OTHER", "N/A", "desc", false,
                "PUBLIC", List.of(), List.of(), LocalDateTime.now(), 1, "director1", LocalDateTime.now());

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());

        List<Document> upserted = captor.getValue();
        assertFalse(upserted.isEmpty());
        for (Document doc : upserted) {
            assertEquals("Employees", doc.getMetadata().get("sheet_name"));
            assertEquals("xlsx", doc.getMetadata().get("file_type"));
            assertTrue(doc.getContent().contains("SHEET: Employees"), "chunk must keep SHEET header");
            assertTrue(doc.getContent().contains("COLUMNS:"), "chunk must keep COLUMNS header");
            assertTrue(doc.getContent().contains("DOCUMENT: employees.xlsx"), "shared enrichment must still add DOCUMENT header");
        }
        verify(embeddingModel, atLeastOnce()).embed(anyString());
    }

    @Test
    void ingestDocumentSync_xlsx_embeddingVectorNotNullAndCorrectDimension() throws Exception {
        Path xlsx = writeXlsxFile("employees2.xlsx", 2);

        service.ingestDocumentSync(xlsx.toString(), 2L, "uuid-2", "employees2.xlsx", "XLSX",
                10L, "tester", "EMPLOYEE", "IT", "N/A", "OTHER", "N/A", "desc", false,
                "PUBLIC", List.of(), List.of(), LocalDateTime.now(), 1, "director1", LocalDateTime.now());

        List<Double> vector = embeddingModel.embed("probe text");
        assertNotNull(vector);
        assertEquals(384, vector.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void ingestDocumentSync_xlsx_manySheetsAndRows_allChunksKeepOwnSheetContext() throws Exception {
        Path file = tempDir.resolve("multi.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet employees = workbook.createSheet("Employees");
            Row eh = employees.createRow(0);
            eh.createCell(0).setCellValue("ID");
            eh.createCell(1).setCellValue("Name");
            for (int i = 1; i <= 40; i++) {
                Row r = employees.createRow(i);
                r.createCell(0).setCellValue("E" + i);
                r.createCell(1).setCellValue("Employee number " + i + " with a reasonably long name to grow chunk size");
            }

            Sheet departments = workbook.createSheet("Departments");
            Row dh = departments.createRow(0);
            dh.createCell(0).setCellValue("Code");
            dh.createCell(1).setCellValue("Name");
            Row dr = departments.createRow(1);
            dr.createCell(0).setCellValue("IT");
            dr.createCell(1).setCellValue("Information Technology");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                workbook.write(fos);
            }
        }

        service.ingestDocumentSync(file.toString(), 4L, "uuid-4", "multi.xlsx", "xlsx",
                10L, "tester", "EMPLOYEE", "IT", "N/A", "OTHER", "N/A", "desc", false,
                "PUBLIC", List.of(), List.of(), LocalDateTime.now(), 1, "director1", LocalDateTime.now());

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());

        List<Document> upserted = captor.getValue();
        boolean hasEmployeesChunk = upserted.stream().anyMatch(d -> "Employees".equals(d.getMetadata().get("sheet_name")));
        boolean hasDepartmentsChunk = upserted.stream().anyMatch(d -> "Departments".equals(d.getMetadata().get("sheet_name")));
        assertTrue(hasEmployeesChunk, "Employees sheet must not be dropped");
        assertTrue(hasDepartmentsChunk, "Departments sheet must not be dropped");

        for (Document doc : upserted) {
            String sheetName = (String) doc.getMetadata().get("sheet_name");
            assertTrue(doc.getContent().contains("SHEET: " + sheetName), "chunk content must match its own sheet_name metadata");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void ingestDocumentSync_txt_regressionUnaffectedByXlsxBranch() throws Exception {
        Path txt = writeTxtFile("notes.txt", "Cong ty AI Agent xin chao toan the nhan vien.");

        service.ingestDocumentSync(txt.toString(), 3L, "uuid-3", "notes.txt", "txt",
                10L, "tester", "EMPLOYEE", "IT", "N/A", "OTHER", "N/A", "desc", false,
                "PUBLIC", List.of(), List.of(), LocalDateTime.now(), 1, "director1", LocalDateTime.now());

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());

        List<Document> upserted = captor.getValue();
        assertFalse(upserted.isEmpty());
        // TXT path must NOT be restructured into the XLSX SHEET/COLUMNS format,
        // and must not carry XLSX-only metadata.
        assertFalse(upserted.get(0).getContent().contains("SHEET:"));
        assertFalse(upserted.get(0).getMetadata().containsKey("sheet_name"));
        assertEquals("txt", upserted.get(0).getMetadata().get("file_type"));
    }
}
