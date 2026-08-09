package com.aiagent.controller;

import com.aiagent.dto.AuthResponse;
import com.aiagent.dto.GoogleLoginRequest;
import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.RegisterRequest;
import com.aiagent.service.AuthService;
import com.aiagent.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/auth", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
public class AuthApiController {

          private final AuthService authService;
          private final RateLimiterService rateLimiterService;

          @PostMapping("/register")
          public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest registerRequest) {
                    AuthResponse response = authService.register(registerRequest);
                    if (response.isSuccess()) {
                              return ResponseEntity.status(HttpStatus.CREATED).body(response);
                    } else {
                              return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                    }
          }

          // AuthRateLimitFilter đã chặn theo IP thô cho path này (chống flood thuần
          // túy). Ở đây thêm 1 lớp chặn theo IP+email (giống hệt key mà /login/form
          // dùng) vì thân JSON đã được parse sẵn ở tầng controller — tránh brute-force
          // 1 tài khoản cụ thể bằng cách đổi IP mà vẫn không khóa nhầm cả dải IP dùng
          // chung (NAT/văn phòng).
          @PostMapping("/login")
          public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest loginRequest, HttpServletRequest httpRequest) {
                    String key = rateLimitKey(httpRequest, loginRequest.getEmail());
                    if (rateLimiterService.isLoginBlocked(key)) {
                              return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                        .body(AuthResponse.builder()
                                                  .success(false)
                                                  .message("Bạn đã thử đăng nhập quá nhiều lần. Vui lòng thử lại sau.")
                                                  .build());
                    }

                    AuthResponse response = authService.login(loginRequest);
                    if (response.isSuccess()) {
                              rateLimiterService.recordLoginSuccess(key);
                              return ResponseEntity.ok(response);
                    } else {
                              rateLimiterService.recordLoginFailure(key);
                              return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
                    }
          }

          private String rateLimitKey(HttpServletRequest request, String email) {
                    String ip = request.getRemoteAddr();
                    return ip + "|" + (email != null ? email.trim().toLowerCase() : "unknown");
          }

          @PostMapping("/google-login")
          public ResponseEntity<AuthResponse> googleLogin(@RequestBody GoogleLoginRequest googleLoginRequest) {
                    AuthResponse response = authService.googleLogin(googleLoginRequest);
                    if (response.isSuccess()) {
                              return ResponseEntity.ok(response);
                    } else {
                              return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
                    }
          }
}
