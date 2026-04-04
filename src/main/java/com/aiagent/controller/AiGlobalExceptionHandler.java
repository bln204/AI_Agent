package com.aiagent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.ai.retry.NonTransientAiException;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class AiGlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
        
        // Detect 429 Resource Exhausted / Quota Exceeded
        if (message.contains("429") || message.toLowerCase().contains("quota") || message.toLowerCase().contains("exhausted")) {
            log.error("AI Quota Exceeded detected: {}", message);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                    "error", "AI_QUOTA_EXCEEDED",
                    "message", "Hệ thống AI hiện đang hết hạn mức sử dụng (Quota Exceeded). Vui lòng thử lại sau hoặc liên hệ quản trị viên."
                ));
        }

        log.error("Unhandled Exception in AI Controller: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of(
                "error", "INTERNAL_SERVER_ERROR",
                "message", "Đã có lỗi hệ thống xảy ra. Vui lòng thử lại sau."
            ));
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, String>> handleAiException(NonTransientAiException e) {
        log.error("Non-transient AI Exception: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "error", "AI_SERVICE_UNAVAILABLE",
                "message", "Dịch vụ AI hiện không khả dụng. Vui lòng thử lại sau."
            ));
    }
}
