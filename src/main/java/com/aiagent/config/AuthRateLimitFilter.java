package com.aiagent.config;

import com.aiagent.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SEC-011 — throttles the authentication endpoints. Placed before
 * UsernamePasswordAuthenticationFilter so a blocked request never reaches
 * the DaoAuthenticationProvider (no wasted BCrypt work either).
 *
 * /login/form is the real browser login path (native Spring Security form
 * login — see login.html posting directly to it) and is keyed by IP+email
 * combined, so an attacker spamming one victim's email from their IP never
 * blocks that victim's own login from their own device (avoids a trivial
 * account-lockout DoS). /auth/login and /auth/google-login are the JSON
 * REST API and are throttled here by IP only as a coarse flood guard (the
 * body is JSON, not form params, so extracting an identifier — e.g. email
 * — here would require buffering/rewrapping the request); AuthApiController
 * additionally applies a finer IP+email check for /auth/login once the body
 * is already parsed. /auth/google-login now calls out to Google's
 * tokeninfo endpoint per attempt, so IP throttling here also protects
 * against hammering that outbound call.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_FORM_PATH = "/login/form";
    private static final String API_LOGIN_PATH = "/auth/login";
    private static final String API_GOOGLE_LOGIN_PATH = "/auth/google-login";

    private final RateLimiterService rateLimiterService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String contextPath = request.getContextPath();
        String path = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String ip = request.getRemoteAddr();

        if ("POST".equalsIgnoreCase(request.getMethod()) && LOGIN_FORM_PATH.equals(path)) {
            String key = formLoginKey(request, ip);
            if (rateLimiterService.isLoginBlocked(key)) {
                response.sendRedirect(request.getContextPath() + "/login?error=too_many_attempts");
                return;
            }
            filterChain.doFilter(request, response);
            if (response.getStatus() == HttpServletResponse.SC_FOUND
                    || response.getStatus() == HttpServletResponse.SC_MOVED_TEMPORARILY) {
                String location = response.getHeader("Location");
                if (location != null && location.contains("error=true")) {
                    rateLimiterService.recordLoginFailure(key);
                } else {
                    rateLimiterService.recordLoginSuccess(key);
                }
            }
            return;
        }

        if ("POST".equalsIgnoreCase(request.getMethod()) && API_LOGIN_PATH.equals(path)) {
            if (rateLimiterService.isLoginBlocked(ip)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            }
            filterChain.doFilter(request, response);
            if (response.getStatus() == HttpServletResponse.SC_OK) {
                rateLimiterService.recordLoginSuccess(ip);
            } else {
                rateLimiterService.recordLoginFailure(ip);
            }
            return;
        }

        // Cùng bucket theo IP với /auth/login (chia sẻ "loginFailures") — thất bại ở
        // endpoint này cũng tính vào ngưỡng khóa IP chung, tránh việc attacker né
        // limiter bằng cách đổi qua lại giữa 2 endpoint đăng nhập.
        if ("POST".equalsIgnoreCase(request.getMethod()) && API_GOOGLE_LOGIN_PATH.equals(path)) {
            if (rateLimiterService.isLoginBlocked(ip)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            }
            filterChain.doFilter(request, response);
            if (response.getStatus() == HttpServletResponse.SC_OK) {
                rateLimiterService.recordLoginSuccess(ip);
            } else {
                rateLimiterService.recordLoginFailure(ip);
            }
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String formLoginKey(HttpServletRequest request, String ip) {
        String email = request.getParameter("email");
        return ip + "|" + (email != null ? email.trim().toLowerCase() : "unknown");
    }
}
