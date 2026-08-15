package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Department;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentStatus;
import com.aiagent.model.Project;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the approval-lifecycle gate added to canAccessDocument (security
 * scenarios 1-4 of the approved plan): PENDING_APPROVAL/REJECTED documents
 * must be visible ONLY to the DIRECTOR and the uploader, regardless of
 * accessLevel scope -- and the existing PUBLIC/DEPARTMENT/PROJECT scope
 * logic for APPROVED documents must be unaffected.
 */
class DocumentAccessServiceTest {

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    private DocumentAccessService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DocumentAccessService(projectMemberRepository);
    }

    private User user(Long id, String roleCode, Department dept) {
        Role role = new Role();
        role.setCode(roleCode);
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setDepartment(dept);
        return u;
    }

    private Department department(Long id, String code) {
        Department d = new Department();
        d.setId(id);
        d.setCode(code);
        return d;
    }

    private Document document(DocumentStatus status, AccessLevel accessLevel, User uploader) {
        Document doc = new Document();
        doc.setId(42L);
        doc.setStatus(status);
        doc.setAccessLevel(accessLevel);
        doc.setUploadedBy(uploader);
        return doc;
    }

    // --- Scenario 1/2: PENDING_APPROVAL denied to non-owner, non-DIRECTOR ---

    @Test
    void pendingApproval_employee_isDenied() {
        Department hr = department(10L, "HR");
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, hr);
        Document doc = document(DocumentStatus.PENDING_APPROVAL, AccessLevel.DEPARTMENT, uploader);
        doc.setDepartments(Set.of(hr));

        User employeeSameDept = user(2L, RoleConstants.ROLE_EMPLOYEE, hr);

        assertFalse(service.canAccessDocument(employeeSameDept, doc),
                "PENDING document must be denied to an employee even in the same department as the target scope");
    }

    @Test
    void pendingApproval_managerOfDifferentDepartment_isDenied() {
        Department hr = department(10L, "HR");
        Department it = department(20L, "IT");
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, hr);
        Document doc = document(DocumentStatus.PENDING_APPROVAL, AccessLevel.DEPARTMENT, uploader);
        doc.setDepartments(Set.of(hr));

        User otherManager = user(3L, RoleConstants.ROLE_MANAGER, it);

        assertFalse(service.canAccessDocument(otherManager, doc));
    }

    // --- Scenario 3: DIRECTOR always sees PENDING/REJECTED ---

    @Test
    void pendingApproval_director_isAllowed() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.PENDING_APPROVAL, AccessLevel.DEPARTMENT, uploader);

        User director = user(99L, RoleConstants.ROLE_DIRECTOR, null);

        assertTrue(service.canAccessDocument(director, doc));
    }

    @Test
    void rejected_director_isAllowed() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.REJECTED, AccessLevel.DEPARTMENT, uploader);

        User director = user(99L, RoleConstants.ROLE_DIRECTOR, null);

        assertTrue(service.canAccessDocument(director, doc));
    }

    // --- decision #2/#4: uploader can always see their own PENDING/REJECTED ---

    @Test
    void pendingApproval_ownUploader_isAllowed() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.PENDING_APPROVAL, AccessLevel.DEPARTMENT, uploader);

        assertTrue(service.canAccessDocument(uploader, doc));
    }

    @Test
    void rejected_ownUploader_isAllowed() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.REJECTED, AccessLevel.DEPARTMENT, uploader);

        assertTrue(service.canAccessDocument(uploader, doc));
    }

    @Test
    void rejected_otherEmployee_isDenied() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.REJECTED, AccessLevel.PUBLIC, uploader);

        User other = user(4L, RoleConstants.ROLE_EMPLOYEE, department(10L, "HR"));

        assertFalse(service.canAccessDocument(other, doc),
                "REJECTED must be denied to everyone except DIRECTOR/uploader, even for what would otherwise be PUBLIC scope");
    }

    // --- Scenario 6/7/8: APPROVED documents keep the pre-existing scope logic ---

    @Test
    void approved_public_anyAuthenticatedUser_isAllowed() {
        User uploader = user(1L, RoleConstants.ROLE_DIRECTOR, null);
        Document doc = document(DocumentStatus.APPROVED, AccessLevel.PUBLIC, uploader);

        User employee = user(5L, RoleConstants.ROLE_EMPLOYEE, department(10L, "HR"));

        assertTrue(service.canAccessDocument(employee, doc));
    }

    @Test
    void approved_department_matchingDepartment_isAllowed() {
        Department hr = department(10L, "HR");
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, hr);
        Document doc = document(DocumentStatus.APPROVED, AccessLevel.DEPARTMENT, uploader);
        doc.setDepartments(Set.of(hr));

        User sameDept = user(6L, RoleConstants.ROLE_EMPLOYEE, hr);

        assertTrue(service.canAccessDocument(sameDept, doc));
    }

    @Test
    void approved_department_differentDepartment_isDenied() {
        Department hr = department(10L, "HR");
        Department it = department(20L, "IT");
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, hr);
        Document doc = document(DocumentStatus.APPROVED, AccessLevel.DEPARTMENT, uploader);
        doc.setDepartments(Set.of(hr));

        User otherDept = user(7L, RoleConstants.ROLE_EMPLOYEE, it);

        assertFalse(service.canAccessDocument(otherDept, doc));
    }

    @Test
    void approved_project_member_isAllowed() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.APPROVED, AccessLevel.PROJECT, uploader);
        Project project = new Project();
        project.setId(77L);
        doc.setProjects(Set.of(project));

        User member = user(8L, RoleConstants.ROLE_EMPLOYEE, null);
        when(projectMemberRepository.existsByProjectAndUser(project, member)).thenReturn(true);

        assertTrue(service.canAccessDocument(member, doc));
    }

    @Test
    void approved_project_nonMember_isDenied() {
        User uploader = user(1L, RoleConstants.ROLE_MANAGER, department(10L, "HR"));
        Document doc = document(DocumentStatus.APPROVED, AccessLevel.PROJECT, uploader);
        Project project = new Project();
        project.setId(77L);
        doc.setProjects(Set.of(project));

        User nonMember = user(9L, RoleConstants.ROLE_EMPLOYEE, null);
        when(projectMemberRepository.existsByProjectAndUser(any(), any())).thenReturn(false);

        assertFalse(service.canAccessDocument(nonMember, doc));
    }

    @Test
    void nullUser_isAlwaysDenied() {
        Document doc = document(DocumentStatus.APPROVED, AccessLevel.PUBLIC, user(1L, RoleConstants.ROLE_DIRECTOR, null));
        assertFalse(service.canAccessDocument(null, doc));
    }

    @Test
    void nullDocument_isAlwaysDenied() {
        assertFalse(service.canAccessDocument(user(1L, RoleConstants.ROLE_DIRECTOR, null), null));
    }
}
