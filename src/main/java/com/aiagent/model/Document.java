package com.aiagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    // Đường dẫn tới bản PDF phục vụ Document Viewer — tách biệt với filePath
    // (RagReindexService/DocumentIngestionService luôn phải đọc filePath là file
    // gốc). PDF: bằng filePath luôn (không convert). DOCX: null cho tới khi
    // LibreOffice convert xong. TXT/khác: luôn null (viewer không áp dụng).
    @Column(name = "viewer_file_path", length = 500)
    private String viewerFilePath;

    // Trạng thái pipeline Viewer — PENDING (chưa xử lý)/PROCESSING (LibreOffice
    // đang convert)/READY (viewerFilePath đã có)/FAILED (convert lỗi)/
    // UNSUPPORTED (định dạng không có viewer, vd. TXT). Cùng lý do ép VARCHAR
    // như classification bên dưới.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "viewer_status", nullable = false, length = 20)
    private ViewerStatus viewerStatus = ViewerStatus.PENDING;

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

    @Column(length = 255)
    private String decision;

    @Column(nullable = false, unique = true, length = 36)
    private String documentUuid;

    @Column(length = 20)
    private String decisionNumber;

    // classification column is VARCHAR(50) (see V4 migration), not a native
    // MySQL ENUM like access_level below — force VARCHAR mapping since
    // Hibernate 6 otherwise defaults @Enumerated(STRING) to the dialect's
    // native ENUM type on MySQL, which would fail schema validation here.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 50)
    private DocumentClassification classification = DocumentClassification.OTHER;

    @Column(nullable = false)
    private boolean internalSourceFlag = true;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String uploaderRole;

    @Column(length = 100)
    private String departmentName;

    @Column(length = 100)
    private String projectName;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(nullable = false)
    private boolean isDeleted = false;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Transient
    public String getUploaderName() {
        return uploadedBy != null ? uploadedBy.getUsername() : "N/A";
    }

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onPersistOrUpdate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        
        if (documentUuid == null || documentUuid.isBlank()) {
            documentUuid = java.util.UUID.randomUUID().toString();
        }

        if (title != null) {
            this.normalizedTitle = com.aiagent.util.NormalizationUtils.normalize(this.title);
        }

        if (uploadedBy != null) {
            if (uploaderRole == null && uploadedBy.getRole() != null) {
                uploaderRole = uploadedBy.getRole().getName();
            }
            if (departmentName == null && uploadedBy.getDepartment() != null) {
                departmentName = uploadedBy.getDepartment().getName();
            }
        }
    }
}
