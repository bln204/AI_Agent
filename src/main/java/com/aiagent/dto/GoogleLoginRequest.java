package com.aiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GoogleLoginRequest {
          // ID token do Google Identity Services phát hành phía client.
          // Backend PHẢI tự verify token này với Google trước khi tin bất kỳ claim nào
          // (email, sub/googleId) — không được nhận email/googleId trực tiếp từ client.
          private String idToken;
}
