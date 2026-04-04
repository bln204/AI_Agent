package com.aiagent.dto;

import lombok.Data;

@Data
public class ProfileUpdateRequest {
    private String username;
    private String description;
    private String avatarUrl;
    // department và role không cho sửa — không có ở đây
}
