package com.aiagent.dto;

public record ChatMessageDto(
    Long messageId,
    String role,
    String content,
    String status,
    String errorCode,
    boolean retryable
) {}
