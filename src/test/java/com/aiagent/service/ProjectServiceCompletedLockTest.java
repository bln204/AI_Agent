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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * COMPLETED is a terminal project state: once set, NOBODY -- not even
 * DIRECTOR -- may change anything about the project anymore (description,
 * status, members/leader). This is stricter than "frozen" (auto due to an
 * expired deadline, reopenable by Director): COMPLETED has no reopen path.
 */
class ProjectServiceCompletedLockTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private NotificationService notificationService;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProjectService(projectRepository, projectMemberRepository, documentRepository, notificationService);
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

    private Project completedProject() {
        Project p = new Project();
        p.setId(9L);
        p.setName("Test");
        p.setStatus(ProjectStatus.COMPLETED);
        p.setExpectedEndDate(LocalDate.now().minusDays(1));
        return p;
    }

    private Project runningProject() {
        Project p = new Project();
        p.setId(10L);
        p.setName("Test");
        p.setStatus(ProjectStatus.RUNNING);
        p.setExpectedEndDate(LocalDate.now().plusDays(30));
        return p;
    }

    @Test
    void updateDescription_onCompletedProject_isRejected_evenForDirector() {
        Project project = completedProject();
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateDescription(9L, "new desc", director()));
        assertTrue(ex.getMessage().contains("Hoàn thành"));
    }

    @Test
    void updateStatus_onCompletedProject_isRejected_evenForDirector() {
        Project project = completedProject();
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));

        assertThrows(IllegalStateException.class,
                () -> service.updateStatus(9L, ProjectStatus.PAUSED, null, director()));
    }

    @Test
    void addMember_onCompletedProject_isRejected() {
        Project project = completedProject();
        User newMember = director();
        assertThrows(IllegalStateException.class, () -> service.addMember(project, newMember));
    }

    @Test
    void removeMember_onCompletedProject_isRejected() {
        Project project = completedProject();
        User member = director();
        assertThrows(IllegalStateException.class, () -> service.removeMember(project, member));
    }

    @Test
    void setLeader_onCompletedProject_isRejected() {
        Project project = completedProject();
        User newLeader = director();
        assertThrows(IllegalStateException.class, () -> service.setLeader(project, newLeader));
    }

    // --- Regression: a non-completed (RUNNING) project must be unaffected ---

    @Test
    void updateDescription_onRunningProject_stillWorks() {
        Project project = runningProject();
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        Project updated = assertDoesNotThrow(() -> service.updateDescription(10L, "new desc", director()));
        assertTrue(updated.getDescription().equals("new desc"));
    }

    @Test
    void addMember_onRunningProject_stillWorks() {
        Project project = runningProject();
        User newMember = director();
        assertDoesNotThrow(() -> service.addMember(project, newMember));
    }
}
