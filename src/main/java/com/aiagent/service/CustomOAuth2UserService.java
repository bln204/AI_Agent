package com.aiagent.service;

import com.aiagent.model.GoogleUserPending;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.GoogleUserPendingRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final GoogleUserPendingRepository pendingRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        Map<String, Object> attributes = oAuth2User.getAttributes();
        String email    = (String) attributes.get("email");
        String googleId = (String) attributes.get("id");
        String name     = (String) attributes.get("name");
        String picture  = (String) attributes.get("picture");

        // Tìm hoặc tạo user
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> createNewGoogleUser(email, googleId, name, picture));

        // Tài khoản không ACTIVE (Tạm ngưng/vô hiệu hoá) phải bị chặn đăng
        // nhập bất kể phương thức nào — CustomUserDetailsService (form login)
        // đã chặn từ trước, nhưng đường Google OAuth2 này lại thiếu check
        // tương đương nên tài khoản bị tạm ngưng vẫn login được qua Google.
        // Chặn ngay tại đây, TRƯỚC khi cập nhật/lưu googleId hay avatar.
        if (!"ACTIVE".equals(user.getStatus())) {
            log.warn("Từ chối đăng nhập Google cho tài khoản không ACTIVE: {}", email);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_suspended"),
                    "Không thể đăng nhập bằng Google. Vui lòng thử lại.");
        }

        // Cập nhật googleId & avatar nếu chưa có
        if (user.getGoogleId() == null) {
            user.setGoogleId(googleId);
        }
        if (user.getAvatarUrl() == null && picture != null) {
            user.setAvatarUrl(picture);
        }
        userRepository.save(user);

        // Thêm thông tin user vào attributes để dùng trong dashboard
        Map<String, Object> enrichedAttributes = new HashMap<>(attributes);
        enrichedAttributes.put("userId",     user.getId());
        enrichedAttributes.put("username",   user.getUsername());
        enrichedAttributes.put("department", user.getDepartment() != null ? user.getDepartment().getName() : "Chưa cập nhật");
        enrichedAttributes.put("role",       user.getRole()       != null ? user.getRole().getName()       : "Nhân viên");
        enrichedAttributes.put("avatarUrl",  user.getAvatarUrl()  != null ? user.getAvatarUrl()  : picture);
        enrichedAttributes.put("dbUserId",   user.getId());

        // BUG FIX: OAuth2UserAuthority's single-arg constructor hardcodes the
        // authority to "ROLE_USER", completely ignoring the user's actual DB
        // role — so every Spring Security hasRole()/@PreAuthorize check
        // (e.g. MaintenanceController's DIRECTOR-only endpoints) silently
        // failed for every Google-login user regardless of their real role.
        // Mirrors CustomUserDetailsService's (form login) authority mapping
        // exactly, so both login methods grant the same authority for the
        // same DB role — role assignment/user creation logic above is
        // untouched, only how that already-resolved role becomes a
        // Spring Security authority string.
        Set<OAuth2UserAuthority> authorities = Collections.singleton(
                new OAuth2UserAuthority(resolveAuthority(user), enrichedAttributes));

        return new DefaultOAuth2User(authorities, enrichedAttributes, "email");
    }

    /**
     * Same "ROLE_&lt;CODE&gt;" mapping as CustomUserDetailsService (form login).
     * Package-private (not private) so it can be verified directly in tests
     * without mocking the full OAuth2 HTTP userinfo round-trip that
     * loadUser() performs via the DefaultOAuth2UserService superclass.
     */
    String resolveAuthority(User user) {
        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_EMPLOYEE;
        return "ROLE_" + roleCode.toUpperCase();
    }

    /**
     * Google login KHÔNG còn là một đường tự-đăng-ký công khai: chỉ email đã
     * được admin pre-provision (có GoogleUserPending record, chứa sẵn
     * department/role) mới được phép tạo User mới ở lần đăng nhập Google đầu
     * tiên. Email Google bất kỳ không có pending record bị từ chối thẳng —
     * trước đây sẽ tự tạo User mới với role EMPLOYEE mặc định, tương đương
     * self-registration ẩn, đi vòng qua việc xoá /register.
     */
    User createNewGoogleUser(String email, String googleId, String name, String picture) {
        Optional<GoogleUserPending> pending = pendingRepository.findByEmail(email);
        if (pending.isEmpty()) {
            log.warn("Từ chối tạo user Google mới (không có pending record được admin duyệt trước): {}", email);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("google_user_not_provisioned"),
                    "Tài khoản Google này chưa được cấp quyền truy cập hệ thống.");
        }

        log.info("Tạo user mới từ Google (có pending record): {}", email);

        User newUser = new User();
        newUser.setEmail(email);
        newUser.setUsername(generateUsername(name, email));
        newUser.setGoogleId(googleId);
        newUser.setAvatarUrl(picture);
        newUser.setStatus("ACTIVE");

        GoogleUserPending p = pending.get();
        if (p.getDepartment() != null) {
            departmentRepository.findByName(p.getDepartment())
                    .ifPresent(newUser::setDepartment);
        }
        if (p.getRole() != null) {
            roleRepository.findByName(p.getRole())
                    .ifPresent(newUser::setRole);
        }

        log.info("Gán department={} role={} từ pending record",
                newUser.getDepartment(), newUser.getRole());

        return userRepository.save(newUser);
    }

    private String generateUsername(String name, String email) {
        String base = (name != null && !name.isBlank())
                ? name.replaceAll("\\s+", ".").toLowerCase()
                : email.split("@")[0];
        // Đảm bảo unique
        String candidate = base;
        int suffix = 1;
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + suffix++;
        }
        return candidate;
    }
}
