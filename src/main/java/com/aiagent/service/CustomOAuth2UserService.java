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

        Set<OAuth2UserAuthority> authorities = Collections.singleton(
                new OAuth2UserAuthority(enrichedAttributes));

        return new DefaultOAuth2User(authorities, enrichedAttributes, "email");
    }

    private User createNewGoogleUser(String email, String googleId, String name, String picture) {
        log.info("Tạo user mới từ Google: {}", email);

        // Tìm pending record để lấy department & role
        Optional<GoogleUserPending> pending = pendingRepository.findByEmail(email);

        User newUser = new User();
        newUser.setEmail(email);
        newUser.setUsername(generateUsername(name, email));
        newUser.setGoogleId(googleId);
        newUser.setAvatarUrl(picture);
        newUser.setStatus("ACTIVE");

        if (pending.isPresent()) {
            GoogleUserPending p = pending.get();
            if (p.getDepartment() != null) {
                departmentRepository.findByName(p.getDepartment())
                        .ifPresent(newUser::setDepartment);
            }
            if (p.getRole() != null) {
                // Here we might need to map from Role label to Code or Name
                // Since GoogleUserPending has "Trưởng phòng", "Nhân viên"... we search by name
                roleRepository.findByName(p.getRole())
                        .ifPresent(newUser::setRole);
            }
            
            log.info("Gán department={} role={} từ pending record",
                    newUser.getDepartment(), newUser.getRole());
        } else {
            // Mặc định nếu không có pending
            roleRepository.findByCode(RoleConstants.ROLE_EMPLOYEE)
                    .ifPresent(newUser::setRole);
            log.warn("Không tìm thấy pending record cho email: {} — gán mặc định", email);
        }

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
