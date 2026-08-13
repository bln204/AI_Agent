package com.aiagent.repository;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByAccessLevel(AccessLevel accessLevel);

    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.departments")
    List<Document> findAllWithDepartments();

    @Query("SELECT DISTINCT d FROM Document d " +
           "LEFT JOIN FETCH d.departments " +
           "LEFT JOIN FETCH d.projects " +
           "LEFT JOIN FETCH d.uploadedBy")
    List<Document> findAllForReindexing();

    List<Document> findByUploadedBy(User user);

    /**
     * Lấy tất cả tài liệu mà user có quyền truy cập.
     * DIRECTOR: Thấy tất cả.
     * PUBLIC: Tất cả thấy.
     * DEPARTMENT: User thuộc một trong các phòng ban của tài liệu.
     * PROJECT: User là thành viên của ít nhất một dự án của tài liệu.
     * PRIVATE: Chỉ người upload.
     */
    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN d.departments dept LEFT JOIN d.projects proj " +
           "WHERE :roleCode = 'DIRECTOR' " +
           "OR (d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC) " +
           "OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true)) " +
           "OR (d.accessLevel = com.aiagent.model.AccessLevel.PRIVATE AND d.uploadedBy.id = :userId)")
    List<Document> findAccessibleDocuments(@Param("userId") Long userId,
                                           @Param("departmentId") Long departmentId,
                                           @Param("roleCode") String roleCode);

    @Query(value = "SELECT DISTINCT d FROM Document d LEFT JOIN FETCH d.uploadedBy u " +
           "WHERE (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%'))",
           countQuery = "SELECT COUNT(DISTINCT d) FROM Document d WHERE " +
           "(:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%'))")
    Page<Document> findAllAccessibleForDirector(@Param("keyword") String keyword, Pageable pageable);

    @Query(value = "SELECT DISTINCT d FROM Document d " +
           "LEFT JOIN FETCH d.uploadedBy u " +
           "LEFT JOIN d.departments dept " +
           "LEFT JOIN d.projects proj " +
           "WHERE (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND (:roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true)) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PRIVATE AND d.uploadedBy.id = :userId))",
           countQuery = "SELECT COUNT(DISTINCT d) FROM Document d " +
           "LEFT JOIN d.departments dept " +
           "LEFT JOIN d.projects proj " +
           "WHERE (:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND (:roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true)) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PRIVATE AND d.uploadedBy.id = :userId))")
    Page<Document> findAccessibleDocumentsPaginated(
            @Param("userId") Long userId,
            @Param("departmentId") Long departmentId,
            @Param("roleCode") String roleCode,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query(value = "SELECT d FROM Document d LEFT JOIN FETCH d.uploadedBy u WHERE " +
           "(:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC",
           countQuery = "SELECT COUNT(d) FROM Document d WHERE " +
           "(:keyword IS NULL " +
           "  OR LOWER(d.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "  OR LOWER(d.normalizedTitle) LIKE CONCAT('%', :keyword, '%')) " +
           "AND d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC")
    Page<Document> findPublicDocuments(@Param("keyword") String keyword, Pageable pageable);

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
           "AND (:roleCode = 'DIRECTOR' " +
           "  OR d.accessLevel = com.aiagent.model.AccessLevel.PUBLIC " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.DEPARTMENT AND :departmentId IS NOT NULL AND dept.id = :departmentId) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PROJECT AND EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = proj AND pm.user.id = :userId AND pm.active = true)) " +
           "  OR (d.accessLevel = com.aiagent.model.AccessLevel.PRIVATE AND d.uploadedBy.id = :userId))")
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
     * HydrationService already enforces for retrieval).
     */
    java.util.Optional<Document> findByFileHashAndIsDeletedFalse(String fileHash);
    java.util.Optional<Document> findByContentHashAndIsDeletedFalse(String contentHash);

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
}
