package com.aiagent.service;

import com.aiagent.model.Project;
import com.aiagent.model.ProjectStatus;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.repository.ProjectRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * "Ngày gia hạn phải lớn hơn ngày dự kiến kết thúc [hiện tại]" -- covers the
 * pre-existing effectiveDeadline check in ProjectService#updateStatus(EXTENDED)
 * and #reopenProject, which this task's UI change (date-mask + min-date UX)
 * now surfaces to users on the create/extend forms. Locking this in with a
 * test since it previously had none.
 */
class ProjectServiceExtensionValidationTest {

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
    }

    private User director() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        User u = new User();
        u.setId(1L);
        u.setRole(role);
        return u;
    }

    private Project project(LocalDate expectedEndDate) {
        Project p = new Project();
        p.setId(9L);
        p.setName("Test");
        p.setStatus(ProjectStatus.RUNNING);
        p.setExpectedEndDate(expectedEndDate);
        return p;
    }

    @Test
    void updateStatus_extensionDateNotAfterExpectedEndDate_isRejected() {
        LocalDate deadline = LocalDate.now().plusDays(10);
        Project project = project(deadline);
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(9L, ProjectStatus.EXTENDED, deadline, director()));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Ngày gia hạn"));
    }

    @Test
    void updateStatus_extensionDateBeforeExpectedEndDate_isRejected() {
        LocalDate deadline = LocalDate.now().plusDays(10);
        Project project = project(deadline);
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(9L, ProjectStatus.EXTENDED, deadline.minusDays(1), director()));
    }

    @Test
    void updateStatus_extensionDateAfterExpectedEndDate_isAllowed_directorAutoApproves() {
        LocalDate deadline = LocalDate.now().plusDays(10);
        Project project = project(deadline);
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));

        LocalDate newDeadline = deadline.plusDays(1);
        Project updated = assertDoesNotThrow(() -> service.updateStatus(9L, ProjectStatus.EXTENDED, newDeadline, director()));

        assertEquals(ProjectStatus.EXTENDED, updated.getStatus());
        assertEquals(newDeadline, updated.getExtensionDate());
    }
}
