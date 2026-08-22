package com.aiagent.service;

import com.aiagent.model.PasswordResetOtp;
import com.aiagent.model.User;
import com.aiagent.repository.PasswordResetOtpRepository;
import com.aiagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Nghiệp vụ đổi mật khẩu (đã đăng nhập) và quên mật khẩu qua OTP gửi email.
 *
 * Nguyên tắc chống user-enumeration: request OTP luôn trả về cùng một kết
 * quả "thành công chung chung" cho dù email có tồn tại hay không (giống
 * INVALID_CREDENTIALS_MESSAGE trong AuthService) — email OTP thực sự chỉ
 * được gửi nếu email khớp tài khoản trong hệ thống.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    public static final String RESULT_OK = null;
    public static final String RESULT_RATE_LIMITED = "RATE_LIMITED";
    public static final String RESULT_SEND_FAILED = "SEND_FAILED";

    private static final int OTP_LENGTH = 6;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetOtpRepository otpRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final RateLimiterService rateLimiterService;

    @Value("${app.otp.expiration-minutes:5}")
    private int otpExpirationMinutes;

    @Value("${app.otp.max-attempts:5}")
    private int otpMaxAttempts;

    /**
     * @return null nếu request được xử lý (không phân biệt email có tồn tại
     *         hay không), {@link #RESULT_RATE_LIMITED} hoặc {@link #RESULT_SEND_FAILED}
     *         nếu cần báo lỗi cụ thể cho user.
     */
    @Transactional
    public String requestOtp(String rawEmail, String ip) {
        String email = normalizeEmail(rawEmail);
        if (email.isBlank()) {
            return RESULT_OK;
        }

        String emailKey = "otp-req:" + email;
        String ipKey = "otp-req-ip:" + ip;
        if (rateLimiterService.isOtpRequestOnCooldown(emailKey) || rateLimiterService.isOtpRequestOnCooldown(ipKey)) {
            return RESULT_RATE_LIMITED;
        }

        Optional<User> userOptional = userRepository.findByEmail(email);
        if (userOptional.isEmpty()) {
            // Vẫn tốn một chút CPU tương đương nhánh "tồn tại" để giảm chênh lệch
            // thời gian phản hồi rõ rệt nhất có thể (không loại bỏ được hoàn toàn
            // do nhánh tồn tại còn gọi SMTP đồng bộ — xem lưu ý trong báo cáo task).
            passwordEncoder.encode(generateOtp());
            rateLimiterService.markOtpRequested(emailKey);
            rateLimiterService.markOtpRequested(ipKey);
            return RESULT_OK;
        }

        User user = userOptional.get();
        String otpCode = generateOtp();
        String otpHash = passwordEncoder.encode(otpCode);

        otpRepository.deleteByEmail(email);
        PasswordResetOtp otp = new PasswordResetOtp();
        otp.setEmail(email);
        otp.setOtpHash(otpHash);
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(otpExpirationMinutes));
        otp.setUsed(false);
        otp.setAttempts(0);
        otpRepository.save(otp);

        rateLimiterService.markOtpRequested(emailKey);
        rateLimiterService.markOtpRequested(ipKey);

        try {
            mailService.sendOtpEmail(user.getEmail(), otpCode, otpExpirationMinutes);
        } catch (MailException e) {
            log.error("[FORGOT-PASSWORD] Gửi email OTP thất bại cho user id={}: {}", user.getId(), e.getMessage());
            return RESULT_SEND_FAILED;
        }

        log.info("[FORGOT-PASSWORD] Đã gửi OTP cho user id={}", user.getId());
        return RESULT_OK;
    }

    @Transactional
    public boolean verifyOtp(String rawEmail, String otpInput, String ip) {
        String email = normalizeEmail(rawEmail);
        String verifyKey = "otp-verify:" + ip + "|" + email;

        if (rateLimiterService.isOtpVerifyBlocked(verifyKey)) {
            return false;
        }
        if (otpInput == null || otpInput.isBlank()) {
            rateLimiterService.recordOtpVerifyFailure(verifyKey);
            return false;
        }

        Optional<PasswordResetOtp> otpOptional =
                otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email);
        if (otpOptional.isEmpty()) {
            rateLimiterService.recordOtpVerifyFailure(verifyKey);
            return false;
        }

        PasswordResetOtp otp = otpOptional.get();
        if (otp.getExpiresAt().isBefore(LocalDateTime.now()) || otp.getAttempts() >= otpMaxAttempts) {
            otp.setUsed(true);
            otpRepository.save(otp);
            rateLimiterService.recordOtpVerifyFailure(verifyKey);
            return false;
        }

        if (!passwordEncoder.matches(otpInput.trim(), otp.getOtpHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepository.save(otp);
            rateLimiterService.recordOtpVerifyFailure(verifyKey);
            return false;
        }

        otp.setUsed(true);
        otpRepository.save(otp);
        rateLimiterService.recordOtpVerifySuccess(verifyKey);
        return true;
    }

    /** Đặt mật khẩu mới sau khi đã xác thực OTP thành công (chưa đăng nhập). */
    @Transactional
    public String resetPassword(String rawEmail, String newPassword, String confirmPassword) {
        String error = validateNewPassword(newPassword, confirmPassword);
        if (error != null) return error;

        String email = normalizeEmail(rawEmail);
        Optional<User> userOptional = userRepository.findByEmail(email);
        if (userOptional.isEmpty()) {
            return "Không tìm thấy tài khoản.";
        }

        User user = userOptional.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        otpRepository.deleteByEmail(email);
        return null;
    }

    /** Đổi mật khẩu khi đã đăng nhập, yêu cầu xác nhận mật khẩu hiện tại. */
    @Transactional
    public String changePassword(User user, String currentPassword, String newPassword, String confirmPassword) {
        if (currentPassword == null || user.getPassword() == null
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            return "Mật khẩu hiện tại không chính xác.";
        }

        String error = validateNewPassword(newPassword, confirmPassword);
        if (error != null) return error;

        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            return "Mật khẩu mới phải khác mật khẩu hiện tại.";
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return null;
    }

    private String validateNewPassword(String newPassword, String confirmPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return "Mật khẩu mới phải có ít nhất " + MIN_PASSWORD_LENGTH + " ký tự.";
        }
        if (!newPassword.equals(confirmPassword)) {
            return "Xác nhận mật khẩu không khớp.";
        }
        return null;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String generateOtp() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int number = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", number);
    }
}
