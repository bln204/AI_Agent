package com.aiagent.dto;

/**
 * Standard UI response contract for chat messages.
 * Enforces consistency across all execution paths in the ChatApiController.
 * 
 * @param messageId The unique ID of the AI message
 * @param status The current message status (COMPLETED | FAILED | RETRYABLE_ERROR)
 * @param content The actual text content to be displayed in the UI
 * @param errorCode A machine-readable error code (null on success)
 * @param retryable Whether the UI should offer a retry button
 */
public record ChatMessageResponse(
    Long messageId,
    String status,
    String content,
    String errorCode,
    boolean retryable
) {}
