package com.aiagent.repository;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByAccessLevel(AccessLevel accessLevel);

    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.departments")
    List<Document> findAllWithDepartments();

    // Reindex must never touch PENDING_APPROVAL/REJECTED documents -- those are
    // never supposed to reach Qdrant at all (approval gate lives in
    // DocumentService.uploadDocument/approveDocument). Filtering here too is a
    // second, independent safety net for the manual/startup reindex paths.
    // approvedBy is FETCH-joined too: RagReindexService/MaintenanceController
    // read doc.getApprovedBy() outside this query's transaction to build RAG
    // provenance metadata, which would otherwise throw
    // LazyInitializationException on this LAZY association.
    @Query("SELECT DISTINCT d FROM Document d " +
           "LEFT JOIN FETCH d.departments " +
           "LEFT JOIN FETCH d.projects " +
           "LEFT JOIN FETCH d.uploadedBy " +
           "LEFT JOIN FETCH d.approvedBy " +
           "WHERE d.status = com.aiagent.model.DocumentStatus.APPROVED")
    List<Document> findAllForReindexing();

    List<Document> findByUploadedBy(User user);

    /**
     * Lấy tất cả tài liệu mà user có quyền truy cập (không dùng ở đâu hiện tại
     * — giữ lại vì là public repository method, đã đồng bộ status gate với
     * canAccessDocument/findAccessibleDocumentsPaginated cho nhất quán).
     * DIRECTOR: Thấy tất cả tài liệu APPROVED.
     * APPROVED + PUBLIC: Tất cả thấy.
     * APPROVED + DEPARTMENT: User thuộc một trong các phòng ban của tài liệu.
     * APPROVED + PROJECT: User là thành viên của ít nhất một dự án của tài liệu.
     * PENDING_APPROVAL / REJECTED: KHÔNG hiện ở đây kể cả với chính uploader —
     * hai trạng thái này chỉ hiện qua findByStatusVisibleTo (tab "Chờ duyệt" /
     * "Bị từ chối" riêng), tránh trùng lặp/nhầm lẫn với tab công văn đã duyệt.
     */
    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN d.departments dept LEFT JOIN d.projects proj " +
           "WHERE d.status = com.aiagent.model.DocumentStatus.APPROVED AND (" +
           "     :roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true))" +
           ")")
    List<Document> findAccessibleDocuments(@Param("userId") Long userId,
                                           @Param("departmentId") Long departmentId,
                                           @Param("roleCode") String roleCode);

    // General "documents" list page: only APPROVED documents (PENDING/REJECTED
    // belong to the dedicated approval-workflow views instead, see
    // findByStatusVisibleTo below). Director already sees every APPROVED
    // document regardless of scope.
    @Query(value = "SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.uploadedBy u " +
           "WHERE d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%'))",
           countQuery = "SELECT COUNT(DISTINCT d) FROM Document d WHERE " +
           "d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%'))")
    Page<Document> findAllAccessibleForDirector(@Param("keyword") String keyword, Pageable pageable);

    @Query(value = "SELECT DISTINCT d FROM Document d " +
           "LEFT JOIN FETCH d.uploadedBy u " +
           "LEFT JOIN d.departments dept " +
           "LEFT JOIN d.projects proj " +
           "WHERE d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND (" +
           "     :roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true))" +
           ")",
           countQuery = "SELECT COUNT(DISTINCT d) FROM Document d " +
           "LEFT JOIN d.departments dept " +
           "LEFT JOIN d.projects proj " +
           "WHERE d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND (" +
           "     :roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true))" +
           ")")
    Page<Document> findAccessibleDocumentsPaginated(
            @Param("userId") Long userId,
            @Param("departmentId") Long departmentId,
            @Param("roleCode") String roleCode,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query(value = "SELECT d FROM Document d LEFT JOIN FETCH d.uploadedBy u WHERE " +
           "d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC",
           countQuery = "SELECT COUNT(d) FROM Document d WHERE " +
           "d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC")
    Page<Document> findPublicDocuments(@Param("keyword") String keyword, Pageable pageable);

    // "Công văn chờ duyệt" / rejected-documents tabs: DIRECTOR sees every
    // document in the given status regardless of uploader; everyone else
    // (MANAGER) only sees their own submissions in that status (decision
    // #2/#4 — a Manager tracks their own PENDING_APPROVAL/REJECTED uploads,
    // never someone else's).
    @Query(value = "SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.uploadedBy " +
           "WHERE d.status = :status AND (:roleCode = 'DIRECTOR' OR d.uploadedBy.id = :userId)",
           countQuery = "SELECT COUNT(d) FROM Document d " +
           "WHERE d.status = :status AND (:roleCode = 'DIRECTOR' OR d.uploadedBy.id = :userId)")
    Page<Document> findByStatusVisibleTo(@Param("status") com.aiagent.model.DocumentStatus status,
                                         @Param("roleCode") String roleCode,
                                         @Param("userId") Long userId,
                                         Pageable pageable);

    /**
     * Cùng scope với findByStatusVisibleTo ở trên, dùng cho badge "!" trên menu
     * (kiểm tra mỗi lần render trang qua GlobalControllerAdvice) -- EXISTS thay
     * vì phân trang để tránh tải cả list chỉ để biết có/không.
     */
    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Document d " +
           "WHERE d.status = :status AND (:roleCode = 'DIRECTOR' OR d.uploadedBy.id = :userId)")
    boolean existsByStatusVisibleTo(@Param("status") com.aiagent.model.DocumentStatus status,
                                    @Param("roleCode") String roleCode,
                                    @Param("userId") Long userId);

    @Query("SELECT d FROM Document d WHERE d.normalizedTitle = :normalizedTitle")
    List<Document> findByNormalizedTitle(@Param("normalizedTitle") String normalizedTitle);

    @Query("SELECT d FROM Document d WHERE d.normalizedTitle LIKE %:keyword%")
    List<Document> findByNormalizedTitleContaining(@Param("keyword") String keyword);

    @Query(value = "SELECT DISTINCT d FROM Document d " +
           "LEFT JOIN d.departments dept " +
           "LEFT JOIN d.projects proj " +
           "WHERE (" +
           "  LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR d.normalizedTitle LIKE CONCAT('%', :keyword, '%') " +
           "  OR LOWER(d.content) LIKE LOWER(CONCAT('%', :keyword, '%'))" +
           ") " +
           "AND d.status = com.aiagent.model.DocumentStatus.APPROVED " +
           "AND (" +
           "     :roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true))" +
           ")")
    List<Document> findCandidateDocuments(@Param("keyword") String keyword,
                                          @Param("roleCode") String roleCode,
                                          @Param("userId") Long userId,
                                          @Param("departmentId") Long departmentId);

    @Query("SELECT d FROM Document d JOIN d.projects p WHERE p.id = :projectId")
    List<Document> findByProjectId(@Param("projectId") Long projectId);

    java.util.Optional<Document> findByDecisionNumber(String decisionNumber);
    boolean existsByDecisionNumber(String decisionNumber);
    boolean existsByTitle(String title);

    /**
     * Level 1 (exact file) / Level 2 (exact content) duplicate lookups.
     * Excludes soft-deleted rows so a removed document never blocks a
     * re-upload of the same file/content (mirrors the isDeleted invariant
     * HydrationService already enforces for retrieval). Only matches
     * APPROVED documents -- neither REJECTED nor PENDING_APPROVAL block a
     * (re-)submission: a rejection doesn't permanently reserve the file/
     * content hash, and a Manager may freely upload the same content again
     * while an earlier submission is still awaiting a decision.
     */
    java.util.Optional<Document> findByFileHashAndIsDeletedFalseAndStatus(String fileHash, com.aiagent.model.DocumentStatus status);
    java.util.Optional<Document> findByContentHashAndIsDeletedFalseAndStatus(String contentHash, com.aiagent.model.DocumentStatus status);

    java.util.Optional<Document> findByDocumentUuid(String documentUuid);

    @Query("SELECT DISTINCT d FROM Document d " +
           "LEFT JOIN FETCH d.uploadedBy " +
           "LEFT JOIN FETCH d.departments " +
           "LEFT JOIN FETCH d.projects " +
           "WHERE d.id IN :ids")
    List<Document> findAllByIdInWithAssociations(@Param("ids") java.util.Collection<Long> ids);

    @Query("SELECT d FROM Document d WHERE " +
           "(:projectName IS NULL OR d.projectName = :projectName) AND " +
           "(:departmentName IS NULL OR d.departmentName = :departmentName) AND " +
           "(:uploader IS NULL OR d.uploadedBy.username = :uploader) AND " +
           "(:role IS NULL OR d.uploaderRole = :role) AND " +
           "(:decisionNumber IS NULL OR d.decisionNumber = :decisionNumber) AND " +
           "(:documentType IS NULL OR d.fileType = :documentType) AND " +
           "(:classification IS NULL OR d.classification = :classification)")
    List<Document> findWithFilters(@Param("projectName") String projectName,
                                   @Param("departmentName") String departmentName,
                                   @Param("uploader") String uploader,
                                   @Param("role") String role,
                                   @Param("decisionNumber") String decisionNumber,
                                   @Param("documentType") String documentType,
                                   @Param("classification") com.aiagent.model.DocumentClassification classification);

    /**
     * Conditional (atomic) status transitions guarding against double
     * approval / concurrent approval races (decision #9): the WHERE clause
     * only matches a document still PENDING_APPROVAL, so a losing concurrent
     * call affects 0 rows instead of silently double-processing. No @Version
     * field is introduced -- the existing `version` column already has a
     * different meaning (Hydration staleness check) that must not be
     * repurposed.
     */
    @Modifying
    @Query("UPDATE Document d SET d.status = com.aiagent.model.DocumentStatus.APPROVED, d.approvedBy = :approver, d.approvedAt = :approvedAt " +
           "WHERE d.id = :id AND d.status = com.aiagent.model.DocumentStatus.PENDING_APPROVAL")
    int approveIfPending(@Param("id") Long id, @Param("approver") User approver, @Param("approvedAt") LocalDateTime approvedAt);

    @Modifying
    @Query("UPDATE Document d SET d.status = com.aiagent.model.DocumentStatus.REJECTED, d.approvedBy = :approver, d.approvedAt = :approvedAt " +
           "WHERE d.id = :id AND d.status = com.aiagent.model.DocumentStatus.PENDING_APPROVAL")
    int rejectIfPending(@Param("id") Long id, @Param("approver") User approver, @Param("approvedAt") LocalDateTime approvedAt);

    /**
     * Self-healing backfill for environments where the documents.status /
     * access_level='PRIVATE' migration (V7__document_approval.sql) was never
     * run by hand -- e.g. a dev DB relying on ddl-auto=update, which adds the
     * new NOT NULL `status` column without applying its Java-side default to
     * EXISTING rows (MySQL backfills them with '' instead), and never narrows
     * an existing native ENUM column's value set at all. Native SQL so it
     * bypasses JPA/Hibernate's enum mapping entirely -- reading a corrupted
     * ''/PRIVATE value through the entity layer is exactly what throws
     * "No enum constant ...". Idempotent: matches 0 rows once the data is
     * clean, safe to run on every startup (mirrors the rest of
     * DataMigrationService's backfill methods).
     */
    @Modifying
    @Query(value = "INSERT INTO document_departments (document_id, department_id) " +
           "SELECT d.id, COALESCE(u.department_id, (SELECT id FROM departments WHERE code = 'ALL')) " +
           "FROM documents d JOIN users u ON d.uploaded_by = u.id " +
           "WHERE d.access_level = 'PRIVATE' " +
           "  AND NOT EXISTS (SELECT 1 FROM document_departments dd WHERE dd.document_id = d.id)",
           nativeQuery = true)
    int linkLegacyPrivateDocumentsToDepartment();

    @Modifying
    @Query(value = "UPDATE documents SET access_level = 'DEPARTMENT' WHERE access_level = 'PRIVATE'", nativeQuery = true)
    int backfillLegacyPrivateAccessLevel();

    @Modifying
    @Query(value = "UPDATE documents SET status = 'APPROVED' WHERE status IS NULL OR status = ''", nativeQuery = true)
    int backfillBlankStatus();
}
