package com.aiagent.repository;

import com.aiagent.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByCode(String code);
    boolean existsByCode(String code);
    boolean existsByName(String name);

    /**
     * Conditional (atomic) approve của yêu cầu gia hạn Leader đề xuất -- cùng
     * mẫu approveIfPending của DocumentRepository: WHERE chỉ khớp khi vẫn còn
     * pending, tránh race khi 2 request duyệt/từ chối cùng lúc.
     */
    // extensionRequestedBy/extensionRequestedAt KHÔNG bị xoá ở đây (chỉ
    // pendingExtensionDate -- đó mới là cờ "đang có yêu cầu chờ duyệt") để
    // service còn đọc lại được ai đã yêu cầu SAU KHI chạy update này (dùng
    // cho notification), theo đúng mẫu approveIfPending/rejectIfPending của
    // DocumentRepository: bulk update trước, rồi mới findById lần đầu trong
    // transaction để đọc state mới nhất (findById đầu tiên né được cache
    // persistence-context, nếu đọc trước rồi update sau sẽ bị stale).
    @Modifying
    @Query("UPDATE Project p SET p.status = com.aiagent.model.ProjectStatus.EXTENDED, " +
           "p.extensionDate = p.pendingExtensionDate, p.pendingExtensionDate = NULL " +
           "WHERE p.id = :id AND p.pendingExtensionDate IS NOT NULL")
    int approveExtensionIfPending(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Project p SET p.pendingExtensionDate = NULL " +
           "WHERE p.id = :id AND p.pendingExtensionDate IS NOT NULL")
    int rejectExtensionIfPending(@Param("id") Long id);

    /**
     * Self-healing backfill for dev DBs relying on ddl-auto=update (mirrors
     * DocumentRepository.backfillBlankStatus): adding the new NOT NULL status
     * column to an existing non-empty `projects` table can leave old rows with
     * '' instead of the Java-side default, which would throw "No enum
     * constant ProjectStatus." on the very first read through JPA. Native SQL
     * so it never references the `active` column (already dropped by
     * V10__project_lifecycle.sql in an environment where that migration was
     * run by hand) -- this only guarantees a VALID status, it does not try to
     * preserve the old active/inactive value; that nuance is handled by the
     * migration script itself. Idempotent: matches 0 rows once clean.
     */
    @Modifying
    @Query(value = "UPDATE projects SET status = 'RUNNING' WHERE status IS NULL OR status = ''", nativeQuery = true)
    int backfillBlankStatus();
}
