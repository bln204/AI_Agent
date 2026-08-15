package com.aiagent.rag.analyzer;

import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers MetadataVerificationService's ROW_VALUE fallback: a candidate that
 * matches no SQL table (project/department/document/user) — e.g. an XLSX row
 * value like an employee code that only exists inside ingested content, not
 * as a system entity — must still surface as a low-confidence ROW_VALUE
 * entity instead of being silently dropped, so retrieval can still try an
 * exact row-content match.
 */
class MetadataVerificationServiceTest {

    private ProjectRepository projectRepository;
    private DocumentRepository documentRepository;
    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private MetadataVerificationService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        documentRepository = mock(DocumentRepository.class);
        userRepository = mock(UserRepository.class);
        departmentRepository = mock(DepartmentRepository.class);

        when(projectRepository.existsByCode(anyString())).thenReturn(false);
        when(projectRepository.existsByName(anyString())).thenReturn(false);
        when(departmentRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(departmentRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(documentRepository.existsByTitle(anyString())).thenReturn(false);
        when(documentRepository.existsByDecisionNumber(anyString())).thenReturn(false);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);

        service = new MetadataVerificationService(projectRepository, documentRepository, userRepository, departmentRepository);
    }

    @Test
    void candidateMatchingNoSqlTable_fallsBackToRowValue() {
        List<DetectedEntity> result = service.verifyAndResolve(Set.of("NV0003"));

        assertEquals(1, result.size());
        assertEquals(EntityType.ROW_VALUE, result.get(0).getType());
        assertEquals("NV0003", result.get(0).getValue());
    }

    @Test
    void candidateMatchingRealDepartment_isNotDowngradedToRowValue() {
        when(departmentRepository.findByCode("ITD")).thenReturn(Optional.of(new com.aiagent.model.Department()));

        List<DetectedEntity> result = service.verifyAndResolve(Set.of("ITD"));

        assertEquals(1, result.size());
        assertEquals(EntityType.DEPARTMENT, result.get(0).getType());
    }

    @Test
    void shortCandidate_belowMinLength_isIgnoredNotRowValue() {
        List<DetectedEntity> result = service.verifyAndResolve(Set.of("ab"));

        assertTrue(result.isEmpty(), "candidates shorter than 3 chars must not become ROW_VALUE noise");
    }

    @Test
    void mixOfVerifiedAndUnverifiedCandidates_bothSurface() {
        when(userRepository.existsByUsername("director1")).thenReturn(true);

        List<DetectedEntity> result = service.verifyAndResolve(Set.of("director1", "NV0007"));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(e -> e.getType() == EntityType.EMPLOYEE && e.getValue().equals("director1")));
        assertTrue(result.stream().anyMatch(e -> e.getType() == EntityType.ROW_VALUE && e.getValue().equals("NV0007")));
    }
}
