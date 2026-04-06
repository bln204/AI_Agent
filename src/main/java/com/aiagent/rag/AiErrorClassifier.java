package com.aiagent.rag;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility to classify AI model exceptions into actionable categories.
 * Distinguishes between hard quota exhaustion and transient rate limits.
 */
@Slf4j
public final class AiErrorClassifier {

    public static boolean isHardQuotaExceeded(Throwable ex) {
        if (ex == null || ex.getMessage() == null) return false;
        String msg = ex.getMessage();
        
        // RESOURCE_EXHAUSTED check combined with quota/limit terminology
        return msg.contains("RESOURCE_EXHAUSTED") 
            && (msg.contains("quota") || msg.contains("limit"));
    }

    public static boolean isRetryableRateLimit(Throwable ex) {
        if (ex == null || ex.getMessage() == null) return false;
        String msg = ex.getMessage();
        
        // 429 status but not necessarily hard quota
        return msg.contains("429") && !isHardQuotaExceeded(ex);
    }

    public static String getErrorCode(Throwable ex) {
        if (isHardQuotaExceeded(ex)) return "ERR_LLM_HARD_QUOTA";
        if (isRetryableRateLimit(ex)) return "ERR_LLM_RATE_LIMIT";
        return "ERR_LLM_SYSTEM_FAILURE";
    }

    private AiErrorClassifier() {}
}
