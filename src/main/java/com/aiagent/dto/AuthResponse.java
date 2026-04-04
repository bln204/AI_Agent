package com.aiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
          private boolean success;
          private String message;
          private String token;
          private UserInfo user;

          @Data
          @NoArgsConstructor
          @AllArgsConstructor
          @Builder
          public static class UserInfo {
                    private Long id;
                    private String username;
                    private String email;
                    private String department;
                    private String role;
                    private String avatarUrl;
          }
}
