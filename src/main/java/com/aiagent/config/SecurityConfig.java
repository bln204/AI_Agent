package com.aiagent.config;

import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.core.Ordered;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomUserDetailsService customUserDetailsService;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final ClientRegistrationRepository clientRegistrationRepository;

    private OAuth2AuthorizationRequestResolver googleAuthorizationRequestResolver() {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(customizer ->
                customizer.additionalParameters(params -> params.put("prompt", "select_account")));
        return resolver;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public FilterRegistrationBean<CharacterEncodingFilter> encodingFilterRegistration() {
        CharacterEncodingFilter encodingFilter = new CharacterEncodingFilter();
        encodingFilter.setEncoding("UTF-8");
        encodingFilter.setForceEncoding(true);
        FilterRegistrationBean<CharacterEncodingFilter> registrationBean = new FilterRegistrationBean<>(encodingFilter);
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(
                    "/", "/login", "/favicon.ico",
                    "/css/**", "/js/**", "/images/**", "/static/**",
                    "/h2-console/**", "/auth/**"
                ).permitAll()
                // Overall health status must stay reachable without authentication for
                // Docker/orchestrator liveness & readiness probes. Component-level detail
                // exposure is separately gated by management.endpoint.health.show-details
                // (when-authorized + roles) in application.properties, so anonymous callers
                // only ever see the top-level UP/DOWN status.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").authenticated()
                .anyRequest().authenticated()
            )
            // Spring Security sẽ xử lý form login tại /login/form
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login/form")
                .usernameParameter("email")
                .passwordParameter("password")
                .defaultSuccessUrl("/dashboard", true)
                .failureUrl("/login?error=true")
                .permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                // Google keeps its own SSO session in a cookie separate from ours,
                // so without this it silently re-authenticates whichever Google
                // account was last used instead of letting the user pick. This
                // forces Google's account chooser to show every time.
                .authorizationEndpoint(endpoint -> endpoint
                    .authorizationRequestResolver(googleAuthorizationRequestResolver())
                )
                .redirectionEndpoint(redir -> redir
                    .baseUri("/login/oauth2/google")
                )
                .userInfoEndpoint(info -> info
                    .userService(customOAuth2UserService)
                )
                .defaultSuccessUrl("/dashboard", true)
                .failureUrl("/login?error=google")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Spring Security 6 mặc định dùng XorCsrfTokenRequestAttributeHandler
                // (mask token để chống BREACH), chỉ đúng cho flow form HTML render
                // "${_csrf.token}". Nhưng dashboard.js/document-upload đọc thẳng giá
                // trị thô từ cookie XSRF-TOKEN rồi gắn vào header X-XSRF-TOKEN cho
                // fetch() (double-submit cookie pattern) — giá trị đó chưa từng bị
                // mask, nên XorCsrfTokenRequestAttributeHandler unmask sai và luôn
                // reject. CsrfTokenRequestAttributeHandler (plain, không mask) khớp
                // đúng pattern cookie-to-header đang dùng, CSRF vẫn được validate
                // đầy đủ — chỉ bỏ lớp mask không tương thích.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/auth/**", "/h2-console/**")
            )
            .exceptionHandling(exc -> exc
                .authenticationEntryPoint((request, response, authException) -> {
                    if (new AntPathRequestMatcher("/api/**").matches(request)) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    } else {
                        response.sendRedirect("/login");
                    }
                })
            )
            .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .headers(headers -> headers
                // frameOptions is intentionally left at its Spring Security default
                // (X-Frame-Options: DENY) for clickjacking protection. It was
                // previously disabled application-wide, allegedly for the H2
                // console, but spring.h2.console.enabled is never set anywhere in
                // this project (defaults to false) — the H2 console isn't actually
                // reachable, so there was nothing that depended on disabling it.
                .referrerPolicy(referrer -> referrer
                    .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            );

        return http.build();
    }
}
