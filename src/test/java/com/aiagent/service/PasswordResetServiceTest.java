package com.aiagent.service;

import com.aiagent.model.PasswordResetOtp;
import com.aiagent.model.User;
import com.aiagent.repository.PasswordResetOtpRepository;
import com.aiagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the security-sensitive parts of quên-mật-khẩu/đổi-mật-khẩu:
 *  - OTP không bao giờ lưu plaintext (chỉ hash được persist).
 *  - OTP hết hạn / vượt số lần thử tối đa đều bị từ chối.
 *  - requestOtp không tiết lộ email có tồn tại hay không (không gọi mail
 *    service khi email không khớp user nào).
 *  - changePassword bắt buộc đúng mật khẩu hiện tại và không cho đặt trùng
 *    mật khẩu cũ.
 */
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetOtpRepository otpRepository;
    @Mock
    private MailService mailService;
    @Mock
    private RateLimiterService rateLimiterService;

    // BCrypt thật (không mock) để test hash/matches đúng hành vi thực tế.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PasswordResetService(userRepository, otpRepository, passwordEncoder, mailService, rateLimiterService);
        ReflectionTestUtils.setField(service, "otpExpirationMinutes", 5);
        ReflectionTestUtils.setField(service, "otpMaxAttempts", 5);
    }

    private User user(String email) {
        User u = new User();
        u.setId(1L);
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode("OldPass123"));
        return u;
    }

    // ───────────────────────── requestOtp ─────────────────────────

    @Test
    void requestOtp_existingUser_savesHashedOtp_notPlaintext() {
        when(userRepository.findByEmail("user@company.com")).thenReturn(Optional.of(user("user@company.com")));

        String result = service.requestOtp("user@company.com", "127.0.0.1");

        assertNull(result);
        ArgumentCaptor<PasswordResetOtp> captor = ArgumentCaptor.forClass(PasswordResetOtp.class);
        verify(otpRepository).save(captor.capture());
        PasswordResetOtp saved = captor.getValue();

        assertNotNull(saved.getOtpHash());
        assertEquals(60, saved.getOtpHash().length()); // độ dài chuẩn của BCrypt hash
        assertFalse(saved.getOtpHash().matches("\\d{6}"), "OTP hash không được là 6 chữ số thô (plaintext)");
        assertFalse(saved.isUsed());
        assertEquals(0, saved.getAttempts());

        verify(mailService).sendOtpEmail(eq("user@company.com"), anyString(), eq(5));
        verify(otpRepository).deleteByEmail("user@company.com"); // OTP cũ bị vô hiệu hoá
    }

    @Test
    void requestOtp_unknownEmail_doesNotSendMail_butReturnsGenericOk() {
        when(userRepository.findByEmail("ghost@company.com")).thenReturn(Optional.empty());

        String result = service.requestOtp("ghost@company.com", "127.0.0.1");

        assertNull(result, "Không được tiết lộ email không tồn tại qua giá trị trả về khác biệt");
        verify(mailService, never()).sendOtpEmail(anyString(), anyString(), anyInt());
        verify(otpRepository, never()).save(any());
    }

    @Test
    void requestOtp_rateLimited_returnsRateLimited() {
        when(rateLimiterService.isOtpRequestOnCooldown("otp-req:user@company.com")).thenReturn(true);

        String result = service.requestOtp("user@company.com", "127.0.0.1");

        assertEquals(PasswordResetService.RESULT_RATE_LIMITED, result);
        verifyNoInteractions(userRepository, mailService, otpRepository);
    }

    @Test
    void requestOtp_mailSendFailure_returnsSendFailed() {
        when(userRepository.findByEmail("user@company.com")).thenReturn(Optional.of(user("user@company.com")));
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailService).sendOtpEmail(anyString(), anyString(), anyInt());

        String result = service.requestOtp("user@company.com", "127.0.0.1");

        assertEquals(PasswordResetService.RESULT_SEND_FAILED, result);
    }

    // ───────────────────────── verifyOtp ─────────────────────────

    private PasswordResetOtp otpRow(String email, String plainOtp, LocalDateTime expiresAt, int attempts, boolean used) {
        PasswordResetOtp otp = new PasswordResetOtp();
        otp.setEmail(email);
        otp.setOtpHash(passwordEncoder.encode(plainOtp));
        otp.setExpiresAt(expiresAt);
        otp.setAttempts(attempts);
        otp.setUsed(used);
        return otp;
    }

    @Test
    void verifyOtp_correctCode_marksUsedAndReturnsTrue() {
        PasswordResetOtp otp = otpRow("user@company.com", "123456", LocalDateTime.now().plusMinutes(5), 0, false);
        when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@company.com"))
                .thenReturn(Optional.of(otp));

        boolean valid = service.verifyOtp("user@company.com", "123456", "127.0.0.1");

        assertTrue(valid);
        assertTrue(otp.isUsed());
        verify(rateLimiterService).recordOtpVerifySuccess(anyString());
    }

    @Test
    void verifyOtp_wrongCode_incrementsAttempts_andReturnsFalse() {
        PasswordResetOtp otp = otpRow("user@company.com", "123456", LocalDateTime.now().plusMinutes(5), 0, false);
        when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@company.com"))
                .thenReturn(Optional.of(otp));

        boolean valid = service.verifyOtp("user@company.com", "000000", "127.0.0.1");

        assertFalse(valid);
        assertFalse(otp.isUsed());
        assertEquals(1, otp.getAttempts());
        verify(rateLimiterService).recordOtpVerifyFailure(anyString());
    }

    @Test
    void verifyOtp_expiredOtp_isRejected() {
        PasswordResetOtp otp = otpRow("user@company.com", "123456", LocalDateTime.now().minusMinutes(1), 0, false);
        when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@company.com"))
                .thenReturn(Optional.of(otp));

        boolean valid = service.verifyOtp("user@company.com", "123456", "127.0.0.1");

        assertFalse(valid);
        assertTrue(otp.isUsed(), "OTP hết hạn phải bị đánh dấu used để không thể thử lại");
    }

    @Test
    void verifyOtp_maxAttemptsReached_isRejectedEvenWithCorrectCode() {
        PasswordResetOtp otp = otpRow("user@company.com", "123456", LocalDateTime.now().plusMinutes(5), 5, false);
        when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@company.com"))
                .thenReturn(Optional.of(otp));

        boolean valid = service.verifyOtp("user@company.com", "123456", "127.0.0.1");

        assertFalse(valid, "Đã đạt số lần thử tối đa thì phải từ chối kể cả khi nhập đúng OTP");
    }

    @Test
    void verifyOtp_noOtpRequested_isRejected() {
        when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@company.com"))
                .thenReturn(Optional.empty());

        boolean valid = service.verifyOtp("user@company.com", "123456", "127.0.0.1");

        assertFalse(valid);
    }

    @Test
    void verifyOtp_rateLimited_isRejectedWithoutTouchingRepository() {
        when(rateLimiterService.isOtpVerifyBlocked(anyString())).thenReturn(true);

        boolean valid = service.verifyOtp("user@company.com", "123456", "127.0.0.1");

        assertFalse(valid);
        verifyNoInteractions(otpRepository);
    }

    // ───────────────────────── changePassword ─────────────────────────

    @Test
    void changePassword_wrongCurrentPassword_isRejected() {
        User u = user("user@company.com");

        String error = service.changePassword(u, "WrongPass", "NewPass12345", "NewPass12345");

        assertNotNull(error);
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_sameAsCurrentPassword_isRejected() {
        User u = user("user@company.com");

        String error = service.changePassword(u, "OldPass123", "OldPass123", "OldPass123");

        assertNotNull(error);
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_mismatchedConfirmation_isRejected() {
        User u = user("user@company.com");

        String error = service.changePassword(u, "OldPass123", "NewPass12345", "Different12345");

        assertNotNull(error);
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_tooShortNewPassword_isRejected() {
        User u = user("user@company.com");

        String error = service.changePassword(u, "OldPass123", "short", "short");

        assertNotNull(error);
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_valid_encodesAndSaves() {
        User u = user("user@company.com");

        String error = service.changePassword(u, "OldPass123", "NewPass12345", "NewPass12345");

        assertNull(error);
        verify(userRepository).save(u);
        assertTrue(passwordEncoder.matches("NewPass12345", u.getPassword()));
    }

    // ───────────────────────── resetPassword ─────────────────────────

    @Test
    void resetPassword_unknownEmail_isRejected() {
        when(userRepository.findByEmail("ghost@company.com")).thenReturn(Optional.empty());

        String error = service.resetPassword("ghost@company.com", "NewPass12345", "NewPass12345");

        assertNotNull(error);
    }

    @Test
    void resetPassword_valid_encodesSavesAndClearsOtp() {
        User u = user("user@company.com");
        when(userRepository.findByEmail("user@company.com")).thenReturn(Optional.of(u));

        String error = service.resetPassword("user@company.com", "BrandNewPass1", "BrandNewPass1");

        assertNull(error);
        verify(userRepository).save(u);
        verify(otpRepository).deleteByEmail("user@company.com");
        assertTrue(passwordEncoder.matches("BrandNewPass1", u.getPassword()));
    }
}
