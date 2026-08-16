package com.aiagent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class AiGlobalExceptionHandler {

    @Value("${app.upload.max-size-mb:200}")
    private long maxUploadSizeMb;

    // Request quá lớn bị Tomcat/Spring từ chối trước khi tới được controller
    // (thường xảy ra trong CsrfFilter khi đọc multipart parameter), rồi bị forward
    // sang /error và ném lại lần 2 trong lúc dispatch — nên bắt riêng ở đây thay vì
    // rơi vào handler Exception.class chung (sẽ log nhầm thành lỗi hệ thống 500).
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e,
            HttpServletRequest request, HttpServletResponse response) {
        String message = "Tệp tải lên vượt quá dung lượng cho phép (tối đa " + maxUploadSizeMb + "MB).";
        log.warn("Upload rejected - file exceeds max size ({}MB): {}", maxUploadSizeMb, request.getRequestURI());

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(
                    "error", "DOCUMENT_TOO_LARGE",
                    "message", message
                ));
        }

        // Luồng upload qua form HTML (DocumentController) mong đợi redirect kèm
        // flash message, giống hệt cách các catch-block khác trong controller đó
        // báo lỗi — dùng chung cơ chế FlashMap/attribute "error" để không cần sửa template.
        FlashMap flashMap = RequestContextUtils.getOutputFlashMap(request);
        flashMap.put("error", message);
        RequestContextUtils.saveOutputFlashMap("/documents", request, response);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/documents"))
                .build();
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/api/") || uri.startsWith("/ai/");
    }

    // @PreAuthorize denials (e.g. DocumentController's approve/reject
    // endpoints, MaintenanceController's reindex/purge-orphans) land here
    // regardless of whether the endpoint is a JSON API or an MVC form-post —
    // branch the same way handleMaxUploadSizeExceeded above does, so an MVC
    // request still gets its expected redirect + flash "error" instead of a
    // raw JSON body the browser would otherwise render verbatim.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException e,
            HttpServletRequest request, HttpServletResponse response) {
        String message = "Bạn không có quyền thực hiện thao tác này.";

        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "ACCESS_DENIED", "message", message));
        }

        FlashMap flashMap = RequestContextUtils.getOutputFlashMap(request);
        flashMap.put("error", message);
        RequestContextUtils.saveOutputFlashMap("/documents", request, response);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/documents"))
                .build();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of(
                "error", "NOT_FOUND",
                "message", "Không tìm thấy tài nguyên yêu cầu."
            ));
    }

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
