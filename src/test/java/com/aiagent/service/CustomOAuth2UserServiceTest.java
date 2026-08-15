package com.aiagent.service;

import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.GoogleUserPendingRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

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

    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CustomOAuth2UserService(
                mock(UserRepository.class),
                mock(GoogleUserPendingRepository.class),
                mock(RoleRepository.class),
                mock(DepartmentRepository.class));
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
}
