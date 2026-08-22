package com.aiagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;

    // Mốc gia hạn ĐÃ được duyệt (Leader) hoặc tự đặt (Director) -- song song với
    // expectedEndDate, không ghi đè: expectedEndDate là mốc dự kiến gốc, không
    // bao giờ đổi sau khi tạo (theo business rule).
    @Column(name = "extension_date")
    private LocalDate extensionDate;

    // Ngày gia hạn Leader ĐỀ XUẤT, đang chờ Director duyệt (Case 1). Có giá trị
    // <=> đang có 1 yêu cầu gia hạn pending cho project này.
    @Column(name = "pending_extension_date")
    private LocalDate pendingExtensionDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extension_requested_by")
    private User extensionRequestedBy;

    private LocalDateTime extensionRequestedAt;

    @Column(name = "project_type", length = 100)
    private String projectType;

    @Column(precision = 18, scale = 2)
    private BigDecimal cost;

    // VARCHAR-forced như Document.status/classification -- tránh Hibernate 6
    // ép native MySQL ENUM (gây lệch schema validation).
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.RUNNING;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * extensionDate (nếu có) luôn là mốc hiệu lực mới nhất, thay cho
     * expectedEndDate gốc (không bao giờ bị ghi đè -- xem business rule).
     * @Transient để Thymeleaf (${project.effectiveDeadline}) và
     * ProjectService dùng chung 1 nguồn logic duy nhất, không lặp lại.
     */
    @Transient
    public LocalDate getEffectiveDeadline() {
        return extensionDate != null ? extensionDate : expectedEndDate;
    }

    /**
     * Dự án cũ chưa có expectedEndDate không bao giờ đóng băng; dự án đã
     * COMPLETED cũng vậy (đóng băng chỉ áp dụng cho dự án còn trong vòng đời
     * hoạt động). @Transient để cả Thymeleaf (${project.frozen}) lẫn
     * ProjectService.isFrozen() gọi chung, tránh 2 nơi định nghĩa "đóng băng".
     */
    @Transient
    public boolean isFrozen() {
        LocalDate deadline = getEffectiveDeadline();
        if (deadline == null || status == ProjectStatus.COMPLETED) return false;
        return LocalDate.now().isAfter(deadline);
    }
}
