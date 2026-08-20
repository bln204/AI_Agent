package com.aiagent.service;

import com.aiagent.model.Department;
import com.aiagent.model.GoogleUserPending;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.GoogleUserPendingRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Google-login authority mapping bug fix: CustomOAuth2UserService
 * previously used OAuth2UserAuthority's single-arg constructor, which
 * hardcodes the granted authority to "ROLE_USER" regardless of the user's
 * actual DB role — so every Spring Security hasRole()/@PreAuthorize check
 * (e.g. MaintenanceController's DIRECTOR-only endpoints) silently failed for
 * every Google-login user, no matter their real role.
 *
 * loadUser() itself isn't unit-tested here — it delegates to
 * DefaultOAuth2UserService.loadUser() (a real HTTP call to Google's userinfo
 * endpoint), which is superclass behavior this fix does not touch.
 * resolveAuthority() is the extracted, independently-testable piece that
 * this fix actually changes.
 */
class CustomOAuth2UserServiceTest {

    private UserRepository userRepository;
    private GoogleUserPendingRepository pendingRepository;
    private RoleRepository roleRepository;
    private DepartmentRepository departmentRepository;
    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        pendingRepository = mock(GoogleUserPendingRepository.class);
        roleRepository = mock(RoleRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        service = new CustomOAuth2UserService(userRepository, pendingRepository, roleRepository, departmentRepository);
    }

    private User userWithRoleCode(String code) {
        Role role = new Role();
        role.setCode(code);
        User user = new User();
        user.setRole(role);
        return user;
    }

    @Test
    void director_getsDirectorAuthority() {
        assertEquals("ROLE_DIRECTOR", service.resolveAuthority(userWithRoleCode(RoleConstants.ROLE_DIRECTOR)));
    }

    @Test
    void manager_getsManagerAuthority() {
        assertEquals("ROLE_MANAGER", service.resolveAuthority(userWithRoleCode(RoleConstants.ROLE_MANAGER)));
    }

    @Test
    void employee_getsEmployeeAuthority() {
        assertEquals("ROLE_EMPLOYEE", service.resolveAuthority(userWithRoleCode(RoleConstants.ROLE_EMPLOYEE)));
    }

    @Test
    void userWithNoRoleAssigned_defaultsToEmployee_notPrivilegedRole() {
        User user = new User();
        user.setRole(null);

        // Fail-closed: a user record with no role yet must never resolve to
        // a privileged authority (e.g. DIRECTOR) — default to the lowest
        // privilege, mirroring CustomUserDetailsService's form-login fallback.
        assertEquals("ROLE_EMPLOYEE", service.resolveAuthority(user));
    }

    /**
     * Google login must not be a backdoor self-registration path once
     * /register is removed: an unknown Google email with no admin-created
     * GoogleUserPending record has to be rejected, not silently signed up
     * as a new EMPLOYEE.
     */
    @Test
    void createNewGoogleUser_noPendingRecord_isRejected_andNoUserIsPersisted() {
        when(pendingRepository.findByEmail("stranger@gmail.com")).thenReturn(Optional.empty());

        assertThrows(OAuth2AuthenticationException.class, () ->
                service.createNewGoogleUser("stranger@gmail.com", "google-id-1", "Stranger", null));

        verify(userRepository, never()).save(any());
    }

    @Test
    void createNewGoogleUser_withPendingRecord_createsUser_withPendingDepartmentAndRole() {
        GoogleUserPending pending = new GoogleUserPending();
        pending.setEmail("invited@company.com");
        pending.setDepartment("IT");
        pending.setRole("Nhân viên");

        Department department = new Department();
        department.setName("IT");

        Role role = new Role();
        role.setName("Nhân viên");
        role.setCode(RoleConstants.ROLE_EMPLOYEE);

        when(pendingRepository.findByEmail("invited@company.com")).thenReturn(Optional.of(pending));
        when(departmentRepository.findByName("IT")).thenReturn(Optional.of(department));
        when(roleRepository.findByName("Nhân viên")).thenReturn(Optional.of(role));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        User created = service.createNewGoogleUser("invited@company.com", "google-id-2", "Invited Person", "http://pic");

        assertEquals("invited@company.com", created.getEmail());
        assertEquals(department, created.getDepartment());
        assertEquals(role, created.getRole());
        verify(userRepository).save(created);
    }
}
