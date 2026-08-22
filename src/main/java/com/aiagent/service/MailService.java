package com.aiagent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Gửi email OTP cho flow quên mật khẩu. KHÔNG log nội dung OTP (chỉ service
 * gọi phương thức này mới thấy giá trị plaintext, ngay trước khi gửi).
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public void sendOtpEmail(String toEmail, String otpCode, int expirationMinutes) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(toEmail);
        message.setSubject("Mã OTP đặt lại mật khẩu - AI Agent");
        message.setText(
                "Mã OTP đặt lại mật khẩu của bạn là: " + otpCode + "\n\n" +
                "Mã có hiệu lực trong " + expirationMinutes + " phút.\n" +
                "Vui lòng không chia sẻ mã này với bất kỳ ai, kể cả nhân viên hỗ trợ.\n\n" +
                "Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này."
        );
        mailSender.send(message);
    }
}
