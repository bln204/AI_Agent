package com.aiagent.dto;

import lombok.Data;

@Data
public class DocumentRequest {
    private String title;
    private String content;
    private String department;
    // PUBLIC / DEPARTMENT / PRIVATE
    private String accessLevel;
}
