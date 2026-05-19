package com.aiagent.util;

import java.text.Normalizer;

public class NormalizationUtils {

    /**
     * Chuẩn hóa chuỗi tiếng Việt:
     * 1. NFD Normalization (tách dấu)
     * 2. Loại bỏ dấu (accent removal)
     * 3. Chuyển về chữ thường (lowercase)
     * 4. Xóa khoảng trắng thừa (trim + collapse whitespace)
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
}
