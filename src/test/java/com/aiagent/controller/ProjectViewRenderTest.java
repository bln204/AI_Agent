package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.model.Project;
import com.aiagent.model.ProjectMember;
import com.aiagent.model.ProjectStatus;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.DocumentAccessService;
import com.aiagent.service.DocumentService;
import com.aiagent.service.ProjectService;
import com.aiagent.service.RateLimiterService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke-renders /projects and /projects/{id} through the real Thymeleaf
 * templates (not just a compile check) to catch template-syntax breakage --
 * in particular for the dd/mm/yyyy date-mask fields swapped in for the old
 * native &lt;input type="date"&gt; on startDate/expectedEndDate/extensionDate.
 * All collaborators are mocked (matches DocumentApiControllerViewerSecurityTest's
 * pattern), so this never touches a real database.
 */
@WebMvcTest(ProjectController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class ProjectViewRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectService projectService;
    @MockBean
    private DocumentAccessService documentAccessService;
    @MockBean
    private DocumentService documentService;
    @MockBean
    private DocumentRepository documentRepository;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private User director() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        role.setName("Giám đốc");
        User u = new User();
        u.setId(1L);
        u.setUsername("director");
        u.setEmail("director@company.com");
        u.setRole(role);
        return u;
    }

    private Project project() {
        Project p = new Project();
        p.setId(9L);
        p.setCode("PRJ-9");
        p.setName("Dự án mẫu");
        p.setDescription("Mô tả dự án");
        p.setStartDate(LocalDate.of(2026, 1, 15));
        p.setExpectedEndDate(LocalDate.of(2026, 12, 31));
        p.setProjectType("Nội bộ");
        p.setCost(BigDecimal.valueOf(1000000));
        p.setStatus(ProjectStatus.RUNNING);
        return p;
    }

    private void stubCommon(User user, Project project) {
        when(userRepository.findByEmail("director@company.com")).thenReturn(Optional.of(user));
        when(userRepository.findAll()).thenReturn(List.of(user));
        when(documentAccessService.canAccessProjectsPage(any())).thenReturn(true);
        when(documentAccessService.canManageProjects(any())).thenReturn(true);
        when(documentAccessService.canAccessProject(any(), any())).thenReturn(true);
        when(documentAccessService.canUpdateProject(any(), any())).thenReturn(true);
        when(documentAccessService.canManageMembers(any())).thenReturn(true);
        when(documentAccessService.canUploadToProject(any(), any())).thenReturn(true);
        when(documentAccessService.canViewDocumentDetail(any())).thenReturn(true);
        when(projectService.getAllProjects()).thenReturn(List.of(project));
        when(projectService.getProjectById(9L)).thenReturn(Optional.of(project));
        when(projectService.isFrozen(project)).thenReturn(false);
        when(projectService.effectiveDeadline(project)).thenReturn(project.getExpectedEndDate());
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setActive(true);
        member.setLeader(true);
        when(projectService.getProjectMembers(project)).thenReturn(List.of(member));
        when(documentRepository.findByProjectId(9L)).thenReturn(List.of());
    }

    @Test
    @WithMockUser(username = "director@company.com")
    void projectsList_rendersDateMaskFields() throws Exception {
        stubCommon(director(), project());

        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("id=\"startDateDisplay\""),
                        org.hamcrest.Matchers.containsString("id=\"startDateIso\""),
                        org.hamcrest.Matchers.containsString("id=\"expectedEndDateDisplay\""),
                        org.hamcrest.Matchers.containsString("id=\"expectedEndDateIso\""),
                        org.hamcrest.Matchers.containsString("placeholder=\"dd/mm/yyyy\""),
                        org.hamcrest.Matchers.containsString("/js/date-mask.js"),
                        // the calendar-icon shortcut: a sr-only (never visibly
                        // shown) native date input, opened via openDatePicker()
                        org.hamcrest.Matchers.containsString("id=\"startDateNative\""),
                        org.hamcrest.Matchers.containsString("openDatePicker('startDateNative')"),
                        // the visible display field itself must stay type="text",
                        // never revert to the locale-dependent native date input
                        org.hamcrest.Matchers.containsString("id=\"startDateDisplay\" placeholder=\"dd/mm/yyyy\""))));
    }

    @Test
    @WithMockUser(username = "director@company.com")
    void projectDetail_rendersDateMaskFields() throws Exception {
        Project project = project();
        stubCommon(director(), project);

        mockMvc.perform(get("/projects/9"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("id=\"extensionDateDisplay\""),
                        org.hamcrest.Matchers.containsString("id=\"extensionDateInput\""),
                        org.hamcrest.Matchers.containsString("id=\"extensionDateNative\""),
                        org.hamcrest.Matchers.containsString("id=\"reopenExtensionDateDisplay\""),
                        org.hamcrest.Matchers.containsString("id=\"reopenExtensionDateIso\""),
                        org.hamcrest.Matchers.containsString("id=\"reopenExtensionDateNative\""),
                        org.hamcrest.Matchers.containsString("/js/date-mask.js"),
                        // server-rendered readonly dates must stay dd/MM/yyyy (unaffected by this change)
                        org.hamcrest.Matchers.containsString("15/01/2026"))));
    }
}
