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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SEC-004 — DocumentService.saveFile() must never let attacker-controlled
 * filenames influence the physical storage path, and must only accept
 * files whose actual (magic-byte) content matches an allowed document type.
 */
class DocumentServiceUploadSecurityTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentIngestionService documentIngestionService;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private DocumentAccessService documentAccessService;
    @Mock
    private DecisionNumberService decisionNumberService;

    @Mock
    private DocumentDuplicateDetectionService documentDuplicateDetectionService;
    @Mock
    private DocumentViewerConversionService documentViewerConversionService;

    @TempDir
    Path uploadDir;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        documentService = new DocumentService(documentRepository, documentIngestionService,
                departmentRepository, projectRepository, documentAccessService, documentViewerConversionService,
                decisionNumberService, documentDuplicateDetectionService);
        ReflectionTestUtils.setField(documentService, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(documentService, "maxUploadSizeMb", 50L);

        when(documentAccessService.canUpload(any())).thenReturn(true);
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document d = invocation.getArgument(0);
            if (d.getId() == null)
                d.setId(1L);
            return d;
        });
    }

    private User uploader() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        User user = new User();
        user.setId(1L);
        user.setUsername("director");
        user.setRole(role);
        return user;
    }

    private static byte[] genuinePdfBytes() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] genuinePlainTextBytes() {
        return "This is a normal plain text document used for testing.".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] genuineDocxBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Override PartName=\"/word/document.xml\" "
                    + "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p/></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private Document uploadWith(MockMultipartFile file) throws IOException {
        return documentService.uploadDocument(
                "Title", "content", null, null, AccessLevel.PUBLIC, null,
                DocumentClassification.OTHER, null, "desc", true, file, uploader());
    }

    @Test
    void validPdf_isAcceptedAndStoredInsideUploadDir() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", genuinePdfBytes());

        Document doc = uploadWith(file);

        assertNotNull(doc.getFilePath());
        Path stored = Path.of(doc.getFilePath());
        assertTrue(stored.normalize().startsWith(uploadDir.toAbsolutePath().normalize()));
        assertTrue(Files.exists(stored));
        assertFalse(stored.getFileName().toString().contains("report"),
                "physical filename must not echo the client-supplied name");
    }

    @Test
    void validTxt_isAccepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", genuinePlainTextBytes());
        Document doc = uploadWith(file);
        assertNotNull(doc.getFilePath());
        assertTrue(Files.exists(Path.of(doc.getFilePath())));
    }

    @Test
    void validDocx_isAccepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", genuineDocxBytes());
        Document doc = uploadWith(file);
        assertNotNull(doc.getFilePath());
        assertTrue(Files.exists(Path.of(doc.getFilePath())));
    }

    static Stream<String> traversalFilenames() {
        return Stream.of(
                "../../outside.pdf",
                "..\\..\\outside.pdf",
                "/etc/passwd.pdf",
                "C:\\Windows\\win.ini.pdf",
                "....//....//file.pdf");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("traversalFilenames")
    void pathTraversalFilename_isRejected_andNothingWrittenOutsideUploadDir(String maliciousName) {
        MockMultipartFile file = new MockMultipartFile("file", maliciousName, "application/pdf", genuinePdfBytes());

        assertThrows(IllegalArgumentException.class, () -> uploadWith(file));

        // Nothing should ever be written outside the configured upload directory.
        assertTrue(isDirEmptyOfEscapedFiles());
    }

    private boolean isDirEmptyOfEscapedFiles() {
        Path parent = uploadDir.getParent();
        if (parent == null)
            return true;
        try (Stream<Path> siblings = Files.list(parent)) {
            return siblings.filter(p -> !p.equals(uploadDir))
                    .noneMatch(p -> p.getFileName().toString().contains("outside")
                            || p.getFileName().toString().contains("passwd")
                            || p.getFileName().toString().contains("win.ini"));
        } catch (IOException e) {
            return true;
        }
    }

    @Test
    void unsupportedExtension_isRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "script.exe", "application/octet-stream",
                "MZ-fake-exe-header".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> uploadWith(file));
    }

    @Test
    void doubleExtension_withMismatchedContent_isRejected() {
        // Extension says .pdf but content is plain text pretending to be a script ->
        // content mismatch.
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf.exe", "application/pdf",
                "not a real pdf".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> uploadWith(file));
    }

    @Test
    void mimeContentMismatch_extensionSaysPdfButContentIsPlainText_isRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf",
                genuinePlainTextBytes());
        assertThrows(IllegalArgumentException.class, () -> uploadWith(file));
    }

    @Test
    void oversizedFile_isRejected() {
        ReflectionTestUtils.setField(documentService, "maxUploadSizeMb", 1L);
        byte[] oversized = new byte[2 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", oversized);
        assertThrows(IllegalArgumentException.class, () -> uploadWith(file));
    }

    @Test
    void emptyFile_isSkipped_noUploadAttempted() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        Document doc = uploadWith(file);
        // MultipartFile.isEmpty() short-circuits before saveFile() is ever called.
        assertNull(doc.getFilePath());
    }
}
