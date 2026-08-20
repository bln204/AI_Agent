package com.aiagent.config;

import com.aiagent.controller.AuthApiController;
import com.aiagent.dto.AuthResponse;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.AuthService;
import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import com.aiagent.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-011 — verifies brute-force/spam throttling on the real login path
 * (/login/form, native Spring Security form login) and the JSON auth API
 * (/auth/login), while a normal, non-abusive login still succeeds and
 * Google login stays unthrottled.
 */
@WebMvcTest(AuthApiController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class AuthRateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;
    @MockBean
    private AuthService authService;
    @MockBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockBean
    private CustomUserDetailsService customUserDetailsService;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private UserDetails validUser(String email, String rawPassword) {
        return org.springframework.security.core.userdetails.User
                .withUsername(email)
                .password(new BCryptPasswordEncoder().encode(rawPassword))
                .authorities("ROLE_EMPLOYEE")
                .build();
    }

    private MockHttpServletRequestBuilder loginForm(String ip, String email, String password) {
        return post("/login/form")
                .param("email", email)
                .param("password", password)
                .with(csrf())
                .with(fromIp(ip));
    }

    @Test
    void formLogin_repeatedWrongPassword_eventuallyBlocked() throws Exception {
        String email = "victim1@company.com";
        when(customUserDetailsService.loadUserByUsername(email)).thenReturn(validUser(email, "correct-password"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(loginForm("10.0.0.1", email, "wrong-password"))
                    .andExpect(status().is3xxRedirection());
        }

        // 6th attempt: the filter must block before DaoAuthenticationProvider runs again.
        mockMvc.perform(loginForm("10.0.0.1", email, "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("too_many_attempts")));
    }

    @Test
    void formLogin_normalCorrectLogin_isNeverBlocked() throws Exception {
        String email = "gooduser@company.com";
        when(customUserDetailsService.loadUserByUsername(email)).thenReturn(validUser(email, "correct-password"));

        mockMvc.perform(loginForm("10.0.0.2", email, "correct-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("error"))));
    }

    @Test
    void formLogin_differentVictimEmailFromSameAttackerIp_doesNotBlockLegitimateOwner() throws Exception {
        // Attacker spams a victim's email from their own IP...
        String victimEmail = "victim2@company.com";
        when(customUserDetailsService.loadUserByUsername(victimEmail)).thenReturn(validUser(victimEmail, "real-password"));

        for (int i = 0; i < 6; i++) {
            mockMvc.perform(loginForm("10.0.0.3", victimEmail, "guess"))
                    .andExpect(status().is3xxRedirection());
        }

        // ...but the real victim, logging in correctly from their OWN ip, is unaffected
        // (combined IP+email key means the attacker's IP is blocked, not the victim's account).
        mockMvc.perform(loginForm("10.0.0.99", victimEmail, "real-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("error"))));
    }

    @Test
    void apiLogin_repeatedFailures_eventuallyThrottled() throws Exception {
        when(authService.login(any())).thenReturn(AuthResponse.builder().success(false).message("bad creds").build());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"x@x.com\",\"password\":\"wrong\"}")
                            .with(fromIp("10.0.0.4")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"x@x.com\",\"password\":\"wrong\"}")
                        .with(fromIp("10.0.0.4")))
                .andExpect(status().isTooManyRequests());
    }
}
