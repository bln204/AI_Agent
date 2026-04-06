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

        // 1. Tách dấu (NFD)
        String temp = Normalizer.normalize(input, Normalizer.Form.NFD);
        
        // 2. Bỏ dấu bằng Regex (loại bỏ các Non-spacing Mark)
        String removedAccents = temp.replaceAll("\\p{M}", "");
        
        // 3. Lowercase, Trim, và rút gọn khoảng trắng
        return removedAccents
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }
}
