package com.aiagent.controller;

import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.UserService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Trang quản lý tài khoản đăng nhập hệ thống — CHỈ dành cho role ADMIN (nhân
 * viên phòng Nhân sự kiêm quản trị tài khoản). Không thay thế REST API
 * /api/users hiện có (UserController, cho phép cả ADMIN lẫn DIRECTOR) —
 * đây là một trang UI riêng, hẹp hơn, đúng theo yêu cầu nghiệp vụ mới.
 */
@Controller
@RequestMapping("/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final UserRepository userRepository;
    private final UserService userService;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;

    @GetMapping
    public String list(Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User requester = resolveUser(authentication);
        if (requester == null) return "redirect:/login";
        if (!isAdmin(requester)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập tài nguyên này.");
            return "redirect:/dashboard";
        }

        model.addAttribute("accounts", userRepository.findAll());
        model.addAttribute("roles", roleRepository.findAll());
        model.addAttribute("departments", departmentRepository.findByActiveTrue().stream()
                .filter(d -> !"ALL".equals(d.getCode()))
                .toList());
        model.addAttribute("currentUser", requester);
        return "admin_accounts";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String username,
                          @RequestParam String email,
                          @RequestParam String password,
                          @RequestParam(required = false) Long roleId,
                          @RequestParam(required = false) Long departmentId,
                          RedirectAttributes redirectAttributes) {
        try {
            User newUser = new User();
            newUser.setUsername(username.trim());
            newUser.setEmail(email.trim());
            newUser.setPassword(password);
            if (roleId != null) {
                roleRepository.findById(roleId).ifPresent(newUser::setRole);
            }
            if (departmentId != null) {
                departmentRepository.findById(departmentId).ifPresent(newUser::setDepartment);
            }
            userService.createUser(newUser);
            redirectAttributes.addFlashAttribute("success", "Đã tạo tài khoản thành công!");
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("error", "Email hoặc tên đăng nhập đã tồn tại trong hệ thống.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Không thể tạo tài khoản. Vui lòng kiểm tra lại thông tin.");
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/{id}/toggle-status")
    @PreAuthorize("hasRole('ADMIN')")
    public String toggleStatus(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        User requester = resolveUser(authentication);
        if (requester != null && requester.getId().equals(id)) {
            redirectAttributes.addFlashAttribute("error", "Không thể tự thay đổi trạng thái của chính tài khoản đang đăng nhập.");
            return "redirect:/admin/accounts";
        }
        try {
            userService.toggleStatus(id);
            redirectAttributes.addFlashAttribute("success", "Đã cập nhật trạng thái tài khoản.");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy tài khoản.");
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        User requester = resolveUser(authentication);
        if (requester != null && requester.getId().equals(id)) {
            redirectAttributes.addFlashAttribute("error", "Không thể tự xoá chính tài khoản đang đăng nhập.");
            return "redirect:/admin/accounts";
        }
        try {
            userService.deleteUser(id);
            redirectAttributes.addFlashAttribute("success", "Đã xoá tài khoản khỏi hệ thống.");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy tài khoản.");
        }
        return "redirect:/admin/accounts";
    }

    private boolean isAdmin(User user) {
        return user.getRole() != null && RoleConstants.ROLE_ADMIN.equals(user.getRole().getCode());
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null) return null;
        Object principal = authentication.getPrincipal();
        String email = null;
        if (principal instanceof OAuth2User oAuth2User) {
            email = oAuth2User.getAttribute("email");
        } else if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername();
        }
        if (email == null) return null;
        return userRepository.findByEmail(email).orElse(null);
    }
}
