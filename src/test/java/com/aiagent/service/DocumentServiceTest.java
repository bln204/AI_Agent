package com.aiagent.service;

import com.aiagent.model.Department;
import com.aiagent.model.Document;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.rag.DocumentIngestionService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentIngestionService documentIngestionService;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private com.aiagent.service.DocumentAccessService documentAccessService;

    @Mock
    private com.aiagent.repository.ProjectRepository projectRepository;

    @Mock
    private com.aiagent.service.DecisionNumberService decisionNumberService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Correct constructor order (from DocumentService.java): repo, ingestion, dept, proj, policy, decisionNumber
        documentService = new DocumentService(documentRepository, documentIngestionService, departmentRepository, projectRepository, documentAccessService, decisionNumberService);
        
        // Fix @Value field
        ReflectionTestUtils.setField(documentService, "uploadDir", "test_uploads");
    }

    @Test
    void uploadDocument_NoUploadPermission_ThrowsException() {
        User uploader = new User();
        uploader.setId(1L);
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_EMPLOYEE);
        uploader.setRole(role);

        when(documentAccessService.canUpload(uploader)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "test".getBytes());

        SecurityException exception = assertThrows(SecurityException.class, () -> {
            documentService.uploadDocument("Title", "Content", java.util.List.of(100L), null, com.aiagent.model.AccessLevel.DEPARTMENT, null, com.aiagent.model.DocumentClassification.OTHER, null, "desc", true, file, uploader);
        });

        assertEquals("Bạn không có quyền tải lên tài liệu.", exception.getMessage());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void uploadDocument_Manager_RestrictsToOwnerDepartment() throws IOException {
        User uploader = new User();
        uploader.setId(2L);
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_MANAGER);
        uploader.setRole(role);
        
        Department dept = new Department();
        dept.setId(10L);
        dept.setName("Nhân sự");
        dept.setCode("HR");
        uploader.setDepartment(dept);

        when(documentAccessService.canUpload(uploader)).thenReturn(true);
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(dept));
        // Fix: Mock findAllById which is used to populate doc.setDepartments
        when(departmentRepository.findAllById(anyList())).thenAnswer(invocation -> {
            java.util.List<Long> ids = invocation.getArgument(0);
            if (ids.contains(10L)) return java.util.List.of(dept);
            return java.util.Collections.emptyList();
        });

        Document mockedDoc = new Document();
        mockedDoc.setId(10L);
        when(documentRepository.save(any(Document.class))).thenReturn(mockedDoc);

        // Call with a different department ID (100L) - should be overridden or filtered
        documentService.uploadDocument("Title", "Content", java.util.List.of(100L), null, com.aiagent.model.AccessLevel.DEPARTMENT, null, com.aiagent.model.DocumentClassification.OTHER, null, "desc", true, null, uploader);

        // verify that the document saved has the uploader's department
        verify(documentRepository, atLeastOnce()).save(argThat(doc -> 
            doc.getDepartments().size() == 1 && 
            doc.getDepartments().iterator().next().getCode().equals("HR")
        ));
    }

    @Test
    void uploadDocument_Director_AllowsAnyDepartment() throws IOException {
        User uploader = new User();
        uploader.setId(3L);
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        uploader.setRole(role);
        
        Department targetDept = new Department();
        targetDept.setId(100L);
        targetDept.setName("IT");
        targetDept.setCode("IT");
        
        when(documentAccessService.canUpload(uploader)).thenReturn(true);
        when(departmentRepository.findById(100L)).thenReturn(Optional.of(targetDept));
        when(departmentRepository.findAllById(anyList())).thenAnswer(invocation -> {
            java.util.List<Long> ids = invocation.getArgument(0);
            if (ids.contains(100L)) return java.util.List.of(targetDept);
            return java.util.Collections.emptyList();
        });

        Document mockedDoc = new Document();
        mockedDoc.setId(10L);
        when(documentRepository.save(any(Document.class))).thenReturn(mockedDoc);

        // Call with department ID 100L
        documentService.uploadDocument("Title", "Content", java.util.List.of(100L), null, com.aiagent.model.AccessLevel.DEPARTMENT, null, com.aiagent.model.DocumentClassification.OTHER, null, "desc", true, null, uploader);

        verify(documentRepository, atLeastOnce()).save(argThat(doc -> 
            doc.getDepartments().size() == 1 && 
            doc.getDepartments().iterator().next().getCode().equals("IT")
        ));
    }
}
