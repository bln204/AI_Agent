package com.aiagent.controller;

import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.DocumentService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * @ControllerAdvice là global -- Spring nạp nó vào MỌI @WebMvcTest slice (kể
 * cả slice chỉ test 1 controller không liên quan gì tới document), nên
 * DocumentService dùng ObjectProvider (optional) thay vì final field bắt
 * buộc: tránh bắt toàn bộ slice test hiện có phải thêm @MockBean cho service
 * này chỉ để thoả mãn dependency injection. Trong app thật (@SpringBootTest /
 * runtime), bean này luôn tồn tại nên getIfAvailable() luôn trả về instance
 * thật -- chỉ null trong slice test hẹp không wire nó, lúc đó badge "!" mặc
 * định ẩn (không sai lệch nghiệp vụ gì, các slice test đó không kiểm tra
 * sidebar).
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalControllerAdvice {

    private final UserRepository userRepository;
    private final ObjectProvider<DocumentService> documentServiceProvider;

    @ModelAttribute
    public void addAttributes(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(auth);
        if (user != null) {
            model.addAttribute("currentUser", user);
            model.addAttribute("userName",   user.getUsername());
            model.addAttribute("userEmail",  user.getEmail());
            model.addAttribute("userRole",   user.getRole() != null ? user.getRole().getName() : "Nhân viên");
            model.addAttribute("userDept",   user.getDepartment() != null ? user.getDepartment().getName() : "Chưa cập nhật");
            model.addAttribute("avatarUrl",  user.getAvatarUrl() != null ? user.getAvatarUrl() : "");

            // Badge "!" trên sidebar cho mục "Công Văn Hội Sở" -- chỉ query khi
            // role thực sự có thể thấy mục menu đó (DIRECTOR/MANAGER), tránh query
            // thừa cho ADMIN/EMPLOYEE ở MỌI request (fragment này render trên mọi
            // trang). Scope chi tiết xem DocumentService#hasPendingApprovalDocuments.
            String roleCode = user.getRole() != null ? user.getRole().getCode() : null;
            boolean isDirectorOrManager = RoleConstants.ROLE_DIRECTOR.equals(roleCode)
                    || RoleConstants.ROLE_MANAGER.equals(roleCode);
            DocumentService documentService = documentServiceProvider.getIfAvailable();
            model.addAttribute("hasPendingDocuments",
                    isDirectorOrManager && documentService != null && documentService.hasPendingApprovalDocuments(user));
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
