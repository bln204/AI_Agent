package com.aiagent.util;

import java.text.Normalizer;

public class NormalizationUtils {

    /**
     * Chuẩn hóa Unicode cho nội dung hiển thị (tài liệu, câu hỏi người dùng, ...):
     * 1. NFC Normalization (chuẩn hóa cách biểu diễn dấu, không tách/xóa dấu)
     * 2. Xóa khoảng trắng thừa (trim + collapse whitespace)
     * Giữ nguyên chữ hoa/thường và dấu tiếng Việt — dùng cho mọi nơi cần bảo toàn
     * văn bản gốc (nội dung tài liệu, câu hỏi gửi LLM, so khớp có dấu như "cảm ơn").
     * Để so khớp/tạo cache key không phân biệt hoa-thường và dấu, dùng
     * {@link #normalizeForMatching(String)} thay vì hàm này.
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);

        return normalized
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Chuẩn hóa mạnh cho mục đích so khớp/tạo khóa cache (KHÔNG dùng cho nội dung
     * hiển thị hay lưu trữ, vì sẽ mất dấu tiếng Việt):
     * 1. NFD Normalization (tách dấu)
     * 2. Loại bỏ dấu (accent removal)
     * 3. Chuyển về chữ thường (lowercase)
     * 4. Xóa khoảng trắng thừa (trim + collapse whitespace)
     */
    public static String normalizeForMatching(String input) {
        if (input == null) {
            return "";
        }

        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        String withoutDiacritics = decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        return withoutDiacritics
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }
}
