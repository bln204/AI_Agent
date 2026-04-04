package com.aiagent.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {

          @Value("${jwt.secret:mySecretKeyForJWTTokenGenerationAndValidationPurposesOnly1234567890}")
          private String jwtSecret;

          @Value("${jwt.expiration:86400000}")
          private long jwtExpiration;

          public String generateToken(String email, Long userId) {
                    Date now = new Date();
                    Date expiryDate = new Date(now.getTime() + jwtExpiration);

                    SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

                    return Jwts.builder()
                                        .subject(email)
                                        .claim("userId", userId)
                                        .issuedAt(now)
                                        .expiration(expiryDate)
                                        .signWith(key)
                                        .compact();
          }

          public String getEmailFromToken(String token) {
                    SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
                    Claims claims = Jwts.parser()
                                        .verifyWith(key)
                                        .build()
                                        .parseSignedClaims(token)
                                        .getPayload();
                    return claims.getSubject();
          }

          public Long getUserIdFromToken(String token) {
                    SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
                    Claims claims = Jwts.parser()
                                        .verifyWith(key)
                                        .build()
                                        .parseSignedClaims(token)
                                        .getPayload();
                    return claims.get("userId", Long.class);
          }

          public boolean validateToken(String token) {
                    try {
                              SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
                              Jwts.parser()
                                                  .verifyWith(key)
                                                  .build()
                                                  .parseSignedClaims(token);
                              return true;
                    } catch (Exception ex) {
                              return false;
                    }
          }
}
