package com.aiagent.controller;

import com.aiagent.config.AuthRateLimitFilter;
import com.aiagent.config.SecurityConfig;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.PasswordResetService;
import com.aiagent.service.RateLimiterService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Xác minh ranh giới bảo mật của flow "Đổi mật khẩu" / "Quên mật khẩu":
 *  - GET /change-password và POST /forgot-password/** phải public (permitAll)
 *    để user CHƯA đăng nhập có thể đi hết flow OTP.
 *  - POST /change-password (đổi mật khẩu khi đã đăng nhập) vẫn phải bị chặn
 *    với người dùng anonymous — KHÔNG được nằm trong permitAll.
 *  - POST /reset-password public ở tầng Spring Security nhưng KHÔNG được cho
 *    phép đặt lại mật khẩu nếu HttpSession chưa từng xác thực OTP thành công
 *    (đây là ranh giới bảo mật thực sự cho endpoint này — session-gated).
 */
@WebMvcTest(PasswordController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class PasswordControllerSecurityTest {

    private static final String SESSION_RESET_EMAIL = "PWD_RESET_PENDING_EMAIL";
    private static final String SESSION_RESET_VERIFIED_AT = "PWD_RESET_VERIFIED_AT";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;
    @MockBean
    private PasswordResetService passwordResetService;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private User employee() {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_EMPLOYEE);
        role.setName("Nhân viên");
        User u = new User();
        u.setId(1L);
        u.setUsername("employee");
        u.setEmail("employee@company.com");
        u.setRole(role);
        return u;
    }

    @Test
    void getChangePassword_anonymous_noResetSession_redirectsToForgotPassword() throws Exception {
        mockMvc.perform(get("/change-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));
    }

    @Test
    @WithMockUser(username = "employee@company.com")
    void getChangePassword_authenticated_isOk() throws Exception {
        when(userRepository.findByEmail("employee@company.com")).thenReturn(Optional.of(employee()));

        mockMvc.perform(get("/change-password"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("mode", "authenticated"));
    }

    @Test
    void getChangePassword_anonymous_withValidResetSession_isOk() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_RESET_EMAIL, "employee@company.com");
        session.setAttribute(SESSION_RESET_VERIFIED_AT, LocalDateTime.now());

        mockMvc.perform(get("/change-password").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("mode", "reset"));
    }

    @Test
    void postChangePassword_anonymous_isRejectedBeforeReachingController() throws Exception {
        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", "whatever")
                        .param("newPassword", "newpassword123")
                        .param("confirmPassword", "newpassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    void postResetPassword_withoutVerifiedOtpSession_isRejectedAndDoesNotTouchService() throws Exception {
        // Không có session nào — mô phỏng attacker gọi thẳng /reset-password mà
        // chưa từng đi qua bước xác thực OTP.
        mockMvc.perform(post("/reset-password").with(csrf())
                        .param("newPassword", "hacked12345")
                        .param("confirmPassword", "hacked12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));

        verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void postResetPassword_withExpiredResetSession_isRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_RESET_EMAIL, "employee@company.com");
        // đã xác thực OTP nhưng quá 10 phút -> phải bị coi là hết hạn
        session.setAttribute(SESSION_RESET_VERIFIED_AT, LocalDateTime.now().minusMinutes(11));

        mockMvc.perform(post("/reset-password").with(csrf()).session(session)
                        .param("newPassword", "hacked12345")
                        .param("confirmPassword", "hacked12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));

        verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void postResetPassword_withValidVerifiedSession_callsService() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_RESET_EMAIL, "employee@company.com");
        session.setAttribute(SESSION_RESET_VERIFIED_AT, LocalDateTime.now());
        when(passwordResetService.resetPassword(eq("employee@company.com"), anyString(), anyString()))
                .thenReturn(null);

        mockMvc.perform(post("/reset-password").with(csrf()).session(session)
                        .param("newPassword", "newpassword123")
                        .param("confirmPassword", "newpassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verify(passwordResetService).resetPassword(eq("employee@company.com"), eq("newpassword123"), eq("newpassword123"));
    }

    @Test
    void getForgotPassword_isPubliclyReachable() throws Exception {
        mockMvc.perform(get("/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("step", "email"));
    }

    @Test
    void postRequestOtp_isPubliclyReachable() throws Exception {
        when(passwordResetService.requestOtp(anyString(), anyString())).thenReturn(null);

        mockMvc.perform(post("/forgot-password/request-otp").with(csrf())
                        .param("email", "employee@company.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));
    }

    @Test
    void postVerifyOtp_withoutPendingEmail_redirectsWithError() throws Exception {
        mockMvc.perform(post("/forgot-password/verify-otp").with(csrf())
                        .param("otp", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    void postVerifyOtp_wrongCode_doesNotGrantResetSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_RESET_EMAIL, "employee@company.com");
        when(passwordResetService.verifyOtp(eq("employee@company.com"), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(post("/forgot-password/verify-otp").with(csrf()).session(session)
                        .param("otp", "000000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));

        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(SESSION_RESET_VERIFIED_AT));
    }
}
