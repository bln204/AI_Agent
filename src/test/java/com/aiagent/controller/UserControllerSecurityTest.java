package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.RateLimiterService;
import com.aiagent.service.UserService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-015 — verifies /api/users is no longer reachable by anonymous/unauthorized
 * callers and that password never leaves the API.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private User userWithRole(Long id, String email, String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);
        role.setName(roleCode);
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    @Test
    void getAllUsers_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void getAllUsers_employee_isForbidden() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void getUserById_employee_requestingOtherUser_isForbidden() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));

        mockMvc.perform(get("/api/users/999"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void getUserById_employee_requestingSelf_isOk() throws Exception {
        User self = userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE);
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(self));
        when(userService.getUserById(1L)).thenReturn(Optional.of(self));

        mockMvc.perform(get("/api/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void putUser_employee_updatingOtherUser_isForbidden() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));

        mockMvc.perform(put("/api/users/999").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"hacked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void deleteUser_employee_isForbidden() throws Exception {
        when(userRepository.findByEmail("employee@company.com"))
                .thenReturn(Optional.of(userWithRole(1L, "employee@company.com", RoleConstants.ROLE_EMPLOYEE)));

        mockMvc.perform(delete("/api/users/999").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "director@company.com")
    void getAllUsers_director_isOk() throws Exception {
        when(userRepository.findByEmail("director@company.com"))
                .thenReturn(Optional.of(userWithRole(2L, "director@company.com", RoleConstants.ROLE_DIRECTOR)));
        when(userService.getAllUsers()).thenReturn(List.of());

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "director@company.com")
    void deleteUser_director_isOk() throws Exception {
        when(userRepository.findByEmail("director@company.com"))
                .thenReturn(Optional.of(userWithRole(2L, "director@company.com", RoleConstants.ROLE_DIRECTOR)));

        mockMvc.perform(delete("/api/users/999").with(csrf()))
                .andExpect(status().isNoContent());
    }
}
