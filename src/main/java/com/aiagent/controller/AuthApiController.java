package com.aiagent.controller;

import com.aiagent.dto.AuthResponse;
import com.aiagent.dto.GoogleLoginRequest;
import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.RegisterRequest;
import com.aiagent.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/auth", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
public class AuthApiController {

          private final AuthService authService;

          @PostMapping("/register")
          public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest registerRequest) {
                    AuthResponse response = authService.register(registerRequest);
                    if (response.isSuccess()) {
                              return ResponseEntity.status(HttpStatus.CREATED).body(response);
                    } else {
                              return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                    }
          }

          @PostMapping("/login")
          public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest loginRequest) {
                    AuthResponse response = authService.login(loginRequest);
                    if (response.isSuccess()) {
                              return ResponseEntity.ok(response);
                    } else {
                              return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
                    }
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
