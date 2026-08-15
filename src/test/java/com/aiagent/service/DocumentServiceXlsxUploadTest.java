package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.util.RoleConstants;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the upload-time XLSX branch in DocumentService#uploadDocument: the
 * duplicate-check text extraction and the preSplitChunks fed to
 * DocumentIngestionService#ingestPreExtracted must use the structured
 * SHEET/COLUMNS/ROW chunker instead of the generic TokenTextSplitter, for a
 * genuine (Apache-POI generated) .xlsx file that passes the real Tika
 * magic-byte MIME check in determineValidatedExtension.
 */
class DocumentServiceXlsxUploadTest {

    private DocumentRepository documentRepository;
    private DocumentIngestionService documentIngestionService;
    private DepartmentRepository departmentRepository;
    private ProjectRepository projectRepository;
    private DocumentAccessService documentAccessService;
    private DecisionNumberService decisionNumberService;
    private DocumentDuplicateDetectionService documentDuplicateDetectionService;
    private DocumentViewerConversionService documentViewerConversionService;
    private NotificationService notificationService;

    private DocumentService documentService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        MockitoAnnotations.openMocks(this);
        documentRepository = mock(DocumentRepository.class);
        documentIngestionService = mock(DocumentIngestionService.class);
        departmentRepository = mock(DepartmentRepository.class);
        projectRepository = mock(ProjectRepository.class);
        documentAccessService = mock(DocumentAccessService.class);
        decisionNumberService = mock(DecisionNumberService.class);
        documentDuplicateDetectionService = mock(DocumentDuplicateDetectionService.class);
        documentViewerConversionService = mock(DocumentViewerConversionService.class);
        notificationService = mock(NotificationService.class);

        documentService = new DocumentService(documentRepository, documentIngestionService, departmentRepository,
                projectRepository, documentAccessService, documentViewerConversionService, notificationService,
                decisionNumberService, documentDuplicateDetectionService);
        ReflectionTestUtils.setField(documentService, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(documentService, "maxUploadSizeMb", 50L);

        when(documentAccessService.canUpload(any())).thenReturn(true);
        when(documentDuplicateDetectionService.hashBytes(any())).thenReturn("dummy-file-hash");
        when(documentDuplicateDetectionService.hashText(any())).thenReturn("dummy-content-hash");
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getId() == null) {
                d.setId(99L);
            }
            return d;
        });
    }

    // Uploader is DIRECTOR (not MANAGER) so the document is APPROVED
    // immediately and ingestPreExtracted actually fires — this test is about
    // xlsx chunking structure, not the approval gate (a MANAGER upload would
    // now be PENDING_APPROVAL and ingestion would correctly be skipped,
    // which would make the verify() calls below fail for an unrelated
    // reason). The approval gate itself is covered by
    // DocumentServiceApprovalTest.
    private User director() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        User user = new User();
        user.setId(1L);
        user.setUsername("director");
        user.setEmail("director@company.com");
        user.setRole(role);
        return user;
    }

    private MockMultipartFile buildXlsxFile(String fileName) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Name");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("E1");
            row.createCell(1).setCellValue("Nguyen Van A");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            workbook.write(bos);
            return new MockMultipartFile("file", fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadDocument_xlsx_preSplitChunksAreStructuredBySheet() throws Exception {
        MockMultipartFile file = buildXlsxFile("employees.xlsx");

        documentService.uploadDocument("Employees", null, List.of(), List.of(), AccessLevel.PUBLIC, null,
                DocumentClassification.OTHER, null, "desc", true, file, director());

        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentIngestionService).ingestPreExtracted(
                captor.capture(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), anyList(), anyList(), any(), any());

        List<org.springframework.ai.document.Document> chunks = captor.getValue();
        assertFalse(chunks.isEmpty());
        assertEquals("Employees", chunks.get(0).getMetadata().get("sheet_name"));
        assertTrue(chunks.get(0).getContent().contains("SHEET: Employees"));
        assertTrue(chunks.get(0).getContent().contains("COLUMNS:"));
        assertTrue(chunks.get(0).getContent().contains("ID | Name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadDocument_txt_regressionStillUsesTokenTextSplitter() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "Cong ty AI Agent xin chao toan the nhan vien.".getBytes());

        documentService.uploadDocument("Notes", null, List.of(), List.of(), AccessLevel.PUBLIC, null,
                DocumentClassification.OTHER, null, "desc", true, file, director());

        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentIngestionService).ingestPreExtracted(
                captor.capture(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), anyList(), anyList(), any(), any());

        List<org.springframework.ai.document.Document> chunks = captor.getValue();
        assertFalse(chunks.isEmpty());
        assertFalse(chunks.get(0).getMetadata().containsKey("sheet_name"));
        assertFalse(chunks.get(0).getContent().contains("SHEET:"));
    }
}
