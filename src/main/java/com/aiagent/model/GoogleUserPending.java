package com.aiagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "google_user_pending")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GoogleUserPending {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 100)
    private String department; // "Nhân sự", "IT", "Kế toán"

    @Column(length = 50)
    private String role; // "Trưởng phòng", "Nhân viên"

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
