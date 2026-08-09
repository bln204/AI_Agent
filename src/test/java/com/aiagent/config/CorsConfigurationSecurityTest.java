package com.aiagent.config;

import com.aiagent.controller.AuthApiController;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * SEC-012 — CorsConfig (the only CORS rule in the app) mapped /agents/** with
 * allowedOrigins("*"), but no controller, frontend call, or test ever
 * referenced /agents anywhere in the project — verified dead and removed
 * (the whole CorsConfig.java file, since it had no other mapping).
 *
 * Uses the lightweight @WebMvcTest slice (no embedding model / full context)
 * so this doesn't compete for memory with the ONNX-backed @SpringBootTest
 * suite.
 */
@WebMvcTest(AuthApiController.class)
@Import({SecurityConfig.class, AuthRateLimitFilter.class, RateLimiterService.class})
class CorsConfigurationSecurityTest {

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

    @Test
    void agentsPath_wasNeverARealController_remainsUnreachable() throws Exception {
        mockMvc.perform(get("/agents/anything"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void crossOriginRequest_toRealEndpoint_getsNoWildcardCorsHeader() throws Exception {
        mockMvc.perform(get("/login").header("Origin", "https://evil.example.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
