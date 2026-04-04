package com.aiagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

          @Id
          @GeneratedValue(strategy = GenerationType.IDENTITY)
          private Long id;

          @Column(nullable = false, unique = true)
          private String username;

          @Column(nullable = false, unique = true)
          private String email;

          @Column(nullable = true)
          private String password;

          @Column(nullable = true, unique = true)
          private String googleId;

          @Column(length = 500)
          private String avatarUrl;

          @Column(length = 500)
          private String description;

          @ManyToOne(fetch = FetchType.EAGER)
          @JoinColumn(name = "role_id")
          private Role role;

          @ManyToOne(fetch = FetchType.EAGER)
          @JoinColumn(name = "department_id")
          private Department department;

          // Temporary fields for migration
          @Column(name = "department", length = 100)
          private String oldDepartment;

          @Column(name = "role", length = 50)
          private String oldRole;

          @Column(nullable = false)
          private String status;

          @Column(nullable = false)
          private LocalDateTime createdAt;

          private LocalDateTime updatedAt;

          @PrePersist
          protected void onCreate() {
                    createdAt = LocalDateTime.now();
                    status = "ACTIVE";
          }

          @PreUpdate
          protected void onUpdate() {
                    updatedAt = LocalDateTime.now();
          }
}
