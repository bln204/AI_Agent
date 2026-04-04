package com.aiagent.controller;

import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@RequiredArgsConstructor
public class GlobalControllerAdvice {

    private final UserRepository userRepository;

    @ModelAttribute
    public void addAttributes(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user != null) {
            model.addAttribute("currentUser", user);
            // Also keep these for backward compatibility with some existing templates
            model.addAttribute("userName",   user.getUsername());
            model.addAttribute("userEmail",  user.getEmail());
            model.addAttribute("userRole",   user.getRole() != null ? user.getRole().getName() : "Nhân viên");
            model.addAttribute("userDept",   user.getDepartment() != null ? user.getDepartment().getName() : "Chưa cập nhật");
            model.addAttribute("avatarUrl",  user.getAvatarUrl() != null ? user.getAvatarUrl() : "");
        }
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        String email = null;

        if (principal instanceof OAuth2User oAuth2User) {
            email = oAuth2User.getAttribute("email");
        } else if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername(); // email is used as username
        } else if (principal instanceof String s) {
            email = s;
        }

        if (email == null) return null;
        return userRepository.findByEmail(email).orElse(null);
    }
}
