package com.aiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 255)
    private String normalizedTitle;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String filePath;

    @Column(length = 50)
    private String fileType;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "document_departments",
        joinColumns = @JoinColumn(name = "document_id"),
        inverseJoinColumns = @JoinColumn(name = "department_id")
    )
    private Set<Department> departments = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "document_projects",
        joinColumns = @JoinColumn(name = "document_id"),
        inverseJoinColumns = @JoinColumn(name = "project_id")
    )
    private Set<Project> projects = new HashSet<>();

    // Temporary field for migration
    @Column(name = "department_id", insertable = false, updatable = false)
    private Long oldDepartmentId;

    @Column(name = "department", length = 100)
    private String oldDepartment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessLevel accessLevel = AccessLevel.DEPARTMENT;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    // Expose chỉ username để Thymeleaf dùng
    @Transient
    public String getUploaderName() {
        return uploadedBy != null ? uploadedBy.getUsername() : "N/A";
    }

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updateNormalizedTitle();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateNormalizedTitle();
    }

    private void updateNormalizedTitle() {
        if (title != null) {
            String temp = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD);
            this.normalizedTitle = temp.replaceAll("\\p{M}", "").toLowerCase();
        }
    }
}
