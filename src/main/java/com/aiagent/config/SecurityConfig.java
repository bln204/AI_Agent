package com.aiagent.config;

import com.aiagent.service.CustomOAuth2UserService;
import com.aiagent.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomUserDetailsService customUserDetailsService;

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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(
                    "/", "/login", "/register",
                    "/css/**", "/js/**", "/images/**", "/static/**",
                    "/h2-console/**", "/auth/**"
                ).permitAll()
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
                .ignoringRequestMatchers("/auth/**", "/api/**", "/h2-console/**")
            )
            .exceptionHandling(exc -> exc
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendRedirect("/login"))
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.disable())
            );

        return http.build();
    }
}
