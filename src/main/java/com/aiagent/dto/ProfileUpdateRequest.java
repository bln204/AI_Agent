package com.aiagent.dto;

import lombok.Data;

@Data
public class ProfileUpdateRequest {
    private String username;
    private String description;
    private String avatarUrl;
}
