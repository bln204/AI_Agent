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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

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

    public AuthResponse login(LoginRequest loginRequest) {
        Optional<User> userOptional = userRepository.findByEmail(loginRequest.getEmail());

        if (userOptional.isEmpty()) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Không tìm thấy tài khoản, vui lòng thử lại.")
                    .build();
        }

        User user = userOptional.get();

        if (user.getPassword() != null
                && !passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Email hoặc mật khẩu không hợp lệ.")
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
        // Try to find by email first
        Optional<User> userOptional = userRepository.findByEmail(googleLoginRequest.getEmail());

        if (userOptional.isEmpty()) {
            return AuthResponse.builder()
                    .success(false)
                    .message("Không tìm thấy tài khoản, vui lòng thử lại.")
                    .build();
        }

        User user = userOptional.get();

        // Verify if the account is linked with Google or if googleId matches
        if (user.getGoogleId() == null || !user.getGoogleId().equals(googleLoginRequest.getGoogleId())) {
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
}
