package com.aiagent.service;

import com.aiagent.dto.AuthResponse;
import com.aiagent.dto.GoogleLoginRequest;
import com.aiagent.dto.LoginRequest;
import com.aiagent.dto.RegisterRequest;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.JwtTokenProvider;
import com.aiagent.util.RoleConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    // Google chính thức khuyến nghị endpoint tokeninfo để verify ID token phía
    // server mà không cần thêm thư viện google-api-client (rule 32: ưu tiên
    // native/existing capability trước khi thêm dependency mới).
    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";
    private static final HttpClient GOOGLE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    public AuthResponse register(RegisterRequest registerRequest) {
        // Check if user already exists
        Optional<User> existingUser = userRepository.findByEmail(registerRequest.getEmail());
        if (existingUser.isPresent()) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Email đã được sử dụng, vui lòng thử email khác.")
                    .build();
        }

        // Check if username exists
        Optional<User> existingUsername = userRepository.findByUsername(registerRequest.getUsername());
        if (existingUsername.isPresent()) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Tên đăng nhập đã được sử dụng, vui lòng thử tên khác.")
                    .build();
        }

        // Create new user
        User newUser = new User();
        newUser.setUsername(registerRequest.getUsername());
        newUser.setEmail(registerRequest.getEmail());
        newUser.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        
        if (registerRequest.getDepartment() != null && !registerRequest.getDepartment().isEmpty()) {
            departmentRepository.findByName(registerRequest.getDepartment())
                    .ifPresent(newUser::setDepartment);
        }
        
        roleRepository.findByCode(RoleConstants.ROLE_EMPLOYEE)
                .ifPresent(newUser::setRole);
        
        newUser.setStatus("ACTIVE");
        newUser.setCreatedAt(java.time.LocalDateTime.now());
        newUser.setUpdatedAt(java.time.LocalDateTime.now());

        userRepository.save(newUser);

        return AuthResponse.builder()
                .success(true)
                .message("Đăng ký thành công! Vui lòng đăng nhập.")
                .build();
    }

    private static final String INVALID_CREDENTIALS_MESSAGE = "Email hoặc mật khẩu không hợp lệ.";

    public AuthResponse login(LoginRequest loginRequest) {
        Optional<User> userOptional = userRepository.findByEmail(loginRequest.getEmail());

        // Dùng chung 1 message cho cả 2 trường hợp "không tìm thấy tài khoản" và
        // "sai mật khẩu" để tránh lộ thông tin email nào tồn tại trong hệ thống
        // (user enumeration).
        if (userOptional.isEmpty()) {
            return AuthResponse.builder()
                    .success(false)
                    .message(INVALID_CREDENTIALS_MESSAGE)
                    .build();
        }

        User user = userOptional.get();

        if (user.getPassword() == null
                || !passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            return AuthResponse.builder()
                    .success(false)
                    .message(INVALID_CREDENTIALS_MESSAGE)
                    .build();
        }

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getId());

        String deptName = user.getDepartment() != null ? user.getDepartment().getName() : "";
        String roleName = user.getRole() != null ? user.getRole().getName() : "";

        return AuthResponse.builder()
                .success(true)
                .message("Đăng nhập thành công.")
                .token(token)
                .user(new AuthResponse.UserInfo(user.getId(), user.getUsername(),
                        user.getEmail(), deptName, roleName, user.getAvatarUrl()))
                .build();
    }

    public AuthResponse googleLogin(GoogleLoginRequest googleLoginRequest) {
        GoogleTokenClaims claims;
        try {
            claims = verifyGoogleIdToken(googleLoginRequest.getIdToken());
        } catch (Exception e) {
            log.warn("Google ID token verification failed: {}", e.getMessage());
            return AuthResponse.builder()
                    .success(false)
                    .message("Không thể đăng nhập bằng Google. Vui lòng thử lại.")
                    .build();
        }

        // Từ đây trở đi chỉ dùng email/googleId đã được Google xác thực (claims),
        // không dùng bất kỳ giá trị nào client tự khai trong request body.
        Optional<User> userOptional = userRepository.findByEmail(claims.email());

        if (userOptional.isEmpty()) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Không tìm thấy tài khoản, vui lòng thử lại.")
                    .build();
        }

        User user = userOptional.get();

        // Verify if the account is linked with Google or if googleId matches
        if (user.getGoogleId() == null || !user.getGoogleId().equals(claims.googleId())) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Tài khoản này chưa được liên kết với Google.")
                    .build();
        }

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getId());

        String deptName = user.getDepartment() != null ? user.getDepartment().getName() : "";
        String roleName = user.getRole() != null ? user.getRole().getName() : "";

        return AuthResponse.builder()
                .success(true)
                .message("Đăng nhập với Google thành công.")
                .token(token)
                .user(new AuthResponse.UserInfo(user.getId(), user.getUsername(),
                        user.getEmail(), deptName, roleName, user.getAvatarUrl()))
                .build();
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findByGoogleId(String googleId) {
        return userRepository.findByGoogleId(googleId);
    }

    /**
     * Verify Google ID token với Google's tokeninfo endpoint (server-to-server),
     * KHÔNG tin bất kỳ claim nào do client tự gửi kèm token.
     */
    private GoogleTokenClaims verifyGoogleIdToken(String idToken) throws Exception {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Missing Google ID token");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_TOKENINFO_URL + idToken))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = GOOGLE_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("Invalid or expired Google ID token");
        }

        JsonNode claims = objectMapper.readTree(response.body());

        String audience = claims.path("aud").asText("");
        if (!googleClientId.equals(audience)) {
            throw new IllegalArgumentException("Google ID token audience mismatch");
        }

        if (!"true".equals(claims.path("email_verified").asText(""))) {
            throw new IllegalArgumentException("Google email not verified");
        }

        String email = claims.path("email").asText(null);
        String sub = claims.path("sub").asText(null);
        if (email == null || sub == null) {
            throw new IllegalArgumentException("Google ID token missing required claims");
        }

        return new GoogleTokenClaims(email, sub);
    }

    private record GoogleTokenClaims(String email, String googleId) {
    }
}
