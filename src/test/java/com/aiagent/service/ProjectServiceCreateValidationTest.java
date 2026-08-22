package com.aiagent.service;

import com.aiagent.model.Project;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Business rules on project dates at creation time (WORKING_RULES: Minimal
 * Change -- these mirror exactly what the user asked for, nothing broader):
 * startDate >= today, expectedEndDate strictly after startDate. Dates are
 * locked forever after creation (see ProjectService#updateDescription
 * javadoc), so this is the only place these two checks need to live.
 */
class ProjectServiceCreateValidationTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentService documentService;
    @Mock
    private NotificationService notificationService;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProjectService(projectRepository, projectMemberRepository, documentRepository, documentService, notificationService);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectRepository.existsByCode(any())).thenReturn(false);
    }

    @Test
    void startDateBeforeToday_isRejected() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createProject("Test", yesterday, yesterday.plusDays(10), null, null, null, List.of(), null));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Ngày bắt đầu"));
    }

    @Test
    void startDateEqualsToday_isAllowed() {
        LocalDate today = LocalDate.now();
        assertDoesNotThrow(() -> service.createProject("Test", today, today.plusDays(10), null, null, null, List.of(), null));
    }

    @Test
    void startDateInFuture_isAllowed() {
        LocalDate future = LocalDate.now().plusDays(5);
        assertDoesNotThrow(() -> service.createProject("Test", future, future.plusDays(10), null, null, null, List.of(), null));
    }

    @Test
    void expectedEndDateNotAfterStartDate_isRejected() {
        LocalDate today = LocalDate.now();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createProject("Test", today, today, null, null, null, List.of(), null));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("kết thúc"));
    }

    @Test
    void expectedEndDateBeforeStartDate_isRejected() {
        LocalDate today = LocalDate.now();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createProject("Test", today, today.minusDays(1), null, null, null, List.of(), null));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("kết thúc"));
    }

    @Test
    void validDateRange_isAllowed() {
        LocalDate today = LocalDate.now();
        Project result = assertDoesNotThrow(() -> service.createProject("Test", today, today.plusDays(1), null, null, null, List.of(), null));
        org.junit.jupiter.api.Assertions.assertEquals(today, result.getStartDate());
    }
}
