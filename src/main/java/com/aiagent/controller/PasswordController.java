package com.aiagent.controller;

import com.aiagent.dto.ChangePasswordRequest;
import com.aiagent.dto.ForgotPasswordRequest;
import com.aiagent.dto.ResetPasswordRequest;
import com.aiagent.dto.VerifyOtpRequest;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/**
 * Trang "Đổi mật khẩu" (đã đăng nhập) và "Quên mật khẩu" (chưa đăng nhập,
 * xác thực qua OTP gửi email). Xem PasswordResetService cho business logic.
 *
 * Sau khi OTP xác thực đúng, danh tính user được giữ trong HttpSession
 * (RESET_SESSION_EMAIL/RESET_SESSION_VERIFIED_AT) trong một cửa sổ ngắn —
 * đây là ranh giới bảo mật thực sự cho GET /change-password (mode=reset) và
 * POST /reset-password khi request đó KHÔNG có Spring Security authentication
 * (xem SecurityConfig — 2 endpoint này permitAll ở tầng filter, controller
 * tự kiểm tra session thay cho Authentication).
 */
@Controller
@RequiredArgsConstructor
public class PasswordController {

    private static final String SESSION_RESET_EMAIL = "PWD_RESET_PENDING_EMAIL";
    private static final String SESSION_RESET_VERIFIED_AT = "PWD_RESET_VERIFIED_AT";
    private static final long RESET_WINDOW_MINUTES = 10;

    private final UserRepository userRepository;
    private final PasswordResetService passwordResetService;

    // ───────────────────────── Đổi mật khẩu ─────────────────────────

    @GetMapping("/change-password")
    public String changePasswordPage(Authentication authentication, HttpSession session, Model model) {
        if (isAuthenticated(authentication)) {
            model.addAttribute("mode", "authenticated");
            return "change_password";
        }
        if (hasValidResetSession(session)) {
            model.addAttribute("mode", "reset");
            return "change_password";
        }
        return "redirect:/forgot-password";
    }

    /** Chỉ dành cho user đã đăng nhập — path này KHÔNG nằm trong permitAll của SecurityConfig. */
    @PostMapping("/change-password")
    public String changePassword(Authentication authentication,
                                  @ModelAttribute ChangePasswordRequest request,
                                  RedirectAttributes redirectAttributes) {
        if (!isAuthenticated(authentication)) {
            return "redirect:/login";
        }
        User user = resolveUser(authentication);
        if (user == null) {
            return "redirect:/login";
        }

        String error = passwordResetService.changePassword(
                user, request.getCurrentPassword(), request.getNewPassword(), request.getConfirmPassword());
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/change-password";
        }
        redirectAttributes.addFlashAttribute("success", "Đổi mật khẩu thành công!");
        return "redirect:/profile";
    }

    // ───────────────────────── Quên mật khẩu ─────────────────────────

    @GetMapping("/forgot-password")
    public String forgotPasswordPage(HttpSession session, Model model) {
        boolean hasPending = session.getAttribute(SESSION_RESET_EMAIL) != null;
        model.addAttribute("step", hasPending ? "otp" : "email");
        return "forgot_password";
    }

    @PostMapping("/forgot-password/request-otp")
    public String requestOtp(@ModelAttribute ForgotPasswordRequest request,
                              HttpServletRequest httpRequest,
                              HttpSession session,
                              RedirectAttributes redirectAttributes) {
        // Nếu đã có 1 phiên chờ OTP, ưu tiên email của session đó (nút "Gửi lại
        // mã") thay vì tin email client gửi kèm — tránh trường hợp form bị
        // chỉnh sửa để gửi OTP tới email khác trong khi vẫn giữ session cũ.
        String sessionEmail = (String) session.getAttribute(SESSION_RESET_EMAIL);
        String email = sessionEmail != null ? sessionEmail : request.getEmail();
        String ip = httpRequest.getRemoteAddr();

        String outcome = passwordResetService.requestOtp(email, ip);
        if (PasswordResetService.RESULT_RATE_LIMITED.equals(outcome)) {
            redirectAttributes.addFlashAttribute("error", "Bạn vừa yêu cầu mã OTP. Vui lòng thử lại sau ít phút.");
            return "redirect:/forgot-password";
        }
        if (PasswordResetService.RESULT_SEND_FAILED.equals(outcome)) {
            redirectAttributes.addFlashAttribute("error", "Không thể gửi email OTP. Vui lòng thử lại sau.");
            return "redirect:/forgot-password";
        }

        session.setAttribute(SESSION_RESET_EMAIL, email == null ? "" : email.trim().toLowerCase());
        session.removeAttribute(SESSION_RESET_VERIFIED_AT);
        redirectAttributes.addFlashAttribute("success",
                "Nếu email tồn tại trong hệ thống, mã OTP đã được gửi tới email đó.");
        return "redirect:/forgot-password";
    }

    @PostMapping("/forgot-password/verify-otp")
    public String verifyOtp(@ModelAttribute VerifyOtpRequest request,
                             HttpServletRequest httpRequest,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        String email = (String) session.getAttribute(SESSION_RESET_EMAIL);
        if (email == null) {
            redirectAttributes.addFlashAttribute("error", "Phiên quên mật khẩu đã hết hạn. Vui lòng thử lại.");
            return "redirect:/forgot-password";
        }

        boolean valid = passwordResetService.verifyOtp(email, request.getOtp(), httpRequest.getRemoteAddr());
        if (!valid) {
            redirectAttributes.addFlashAttribute("error", "Mã OTP không đúng hoặc đã hết hạn.");
            return "redirect:/forgot-password";
        }

        session.setAttribute(SESSION_RESET_VERIFIED_AT, LocalDateTime.now());
        return "redirect:/change-password";
    }

    /** Bước cuối của flow quên mật khẩu — KHÔNG yêu cầu authentication, chỉ cần session đã verify OTP. */
    @PostMapping("/reset-password")
    public String resetPassword(@ModelAttribute ResetPasswordRequest request,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        if (!hasValidResetSession(session)) {
            redirectAttributes.addFlashAttribute("error", "Phiên đặt lại mật khẩu đã hết hạn. Vui lòng thực hiện lại.");
            return "redirect:/forgot-password";
        }

        String email = (String) session.getAttribute(SESSION_RESET_EMAIL);
        String error = passwordResetService.resetPassword(email, request.getNewPassword(), request.getConfirmPassword());
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/change-password";
        }

        session.removeAttribute(SESSION_RESET_EMAIL);
        session.removeAttribute(SESSION_RESET_VERIFIED_AT);
        redirectAttributes.addFlashAttribute("success", "Đặt lại mật khẩu thành công! Vui lòng đăng nhập.");
        return "redirect:/login";
    }

    // ───────────────────────── Helpers ─────────────────────────

    private boolean hasValidResetSession(HttpSession session) {
        Object verifiedAt = session.getAttribute(SESSION_RESET_VERIFIED_AT);
        Object email = session.getAttribute(SESSION_RESET_EMAIL);
        if (!(verifiedAt instanceof LocalDateTime) || email == null) {
            return false;
        }
        return ((LocalDateTime) verifiedAt).plusMinutes(RESET_WINDOW_MINUTES).isAfter(LocalDateTime.now());
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private User resolveUser(Authentication authentication) {
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
