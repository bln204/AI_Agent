package com.aiagent.rag;

import com.aiagent.model.MessageStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ChatGenerationResult {

    private final boolean success;
    private final String content;
    private final MessageStatus status;
    private final String errorCode;
    private final String errorMessage;
    private final boolean retryable;

    public static ChatGenerationResult success(String content) {
        return new ChatGenerationResult(true, content, MessageStatus.COMPLETED, null, null, false);
    }

    public static ChatGenerationResult retryableError(String errorCode, String message, String fallbackContent) {
        return new ChatGenerationResult(false, fallbackContent, MessageStatus.RETRYABLE_ERROR, errorCode, message, true);
    }

    public static ChatGenerationResult failed(String errorCode, String message, String fallbackContent) {
        return new ChatGenerationResult(false, fallbackContent, MessageStatus.FAILED, errorCode, message, false);
    }
}
