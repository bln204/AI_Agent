package com.aiagent.controller;

import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.dto.ProfileUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;

    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:/dashboard";
        }
        return "login";
    }

    @GetMapping("/register")
    public String register(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:/dashboard";
        }
        return "register";
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication authentication, Model model) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/login";
        }
        User user = resolveUser(authentication);
        if (user != null) {
            model.addAttribute("currentUser", user);
            model.addAttribute("userName",   user.getUsername());
            model.addAttribute("userEmail",  user.getEmail());
            model.addAttribute("userRole",   user.getRole()       != null ? user.getRole().getName()       : "Nhân viên");
            model.addAttribute("userDept",   user.getDepartment() != null ? user.getDepartment().getName() : "Chưa cập nhật");
            model.addAttribute("avatarUrl",  user.getAvatarUrl()  != null ? user.getAvatarUrl()  : "");
        }
        return "dashboard";
    }

    @GetMapping("/profile")
    public String profilePage(Authentication authentication, Model model) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/login";
        }
        User user = resolveUser(authentication);
        if (user != null) {
            model.addAttribute("user", user);
        }
        return "profile";
    }

    @PostMapping("/profile")
    public String updateProfile(Authentication authentication,
                                @ModelAttribute ProfileUpdateRequest request,
                                RedirectAttributes redirectAttributes) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/login";
        }
        User user = resolveUser(authentication);
        if (user != null) {
            // Chỉ cập nhật username, description, avatarUrl — KHÔNG sửa department/role
            if (request.getUsername() != null && !request.getUsername().isBlank()) {
                user.setUsername(request.getUsername());
            }
            if (request.getDescription() != null) {
                user.setDescription(request.getDescription());
            }
            if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
                user.setAvatarUrl(request.getAvatarUrl());
            }
            userRepository.save(user);
            redirectAttributes.addFlashAttribute("success", "Cập nhật profile thành công!");
        }
        return "redirect:/profile";
    }

    // ─── API: lấy thông tin user hiện tại ───────────────────────────────────
    @GetMapping("/api/me")
    @ResponseBody
    public Object currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return java.util.Map.of("error", "Chưa đăng nhập");
        }
        User user = resolveUser(authentication);
        if (user == null) return java.util.Map.of("error", "Không tìm thấy user");

        return java.util.Map.of(
            "id",         user.getId(),
            "username",   user.getUsername(),
            "email",      user.getEmail(),
            "department", user.getDepartment() != null ? user.getDepartment().getName() : "",
            "role",       user.getRole()       != null ? user.getRole().getName()       : "Nhân viên",
            "avatarUrl",  user.getAvatarUrl()  != null ? user.getAvatarUrl()  : ""
        );
    }

    // ─── Helper: lấy User entity từ bất kỳ loại Authentication ─────────────
    private User resolveUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();

        String email = null;
        if (principal instanceof OAuth2User oAuth2User) {
            email = oAuth2User.getAttribute("email");
        } else if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername(); // username field = email
        } else if (principal instanceof String s) {
            email = s;
        }

        if (email == null) return null;
        return userRepository.findByEmail(email).orElse(null);
    }
}
