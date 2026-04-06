package com.aiagent.dto;

/**
 * Standard DTO for message history to maintain field naming consistency.
 */
public record ChatMessageDto(
    Long messageId,
    String role,
    String content,
    String status,
    String errorCode,
    boolean retryable
) {}
