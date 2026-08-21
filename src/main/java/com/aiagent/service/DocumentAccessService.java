package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.DocumentStatus;
import com.aiagent.model.Project;
import com.aiagent.model.User;
import com.aiagent.model.ProjectMember;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentAccessService {

    private final ProjectMemberRepository projectMemberRepository;

    public boolean canAccessDocument(User user, Document doc) {
        if (doc == null) return false;

        // Rule 4.1: PUBLIC vẫn phải yêu cầu user đã authenticated — check null user
        // TRƯỚC nhánh PUBLIC, không để anonymous lọt qua trước guard.
        if (user == null) return false;

        boolean isDirector = user.getRole() != null &&
            RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
        boolean isOwner = doc.getUploadedBy() != null && doc.getUploadedBy().getId().equals(user.getId());

        // Approval lifecycle gate: PENDING_APPROVAL/REJECTED documents are only
        // visible to the DIRECTOR (who approves/rejects) and the uploader
        // (to track their own submission) — regardless of accessLevel scope.
        // This check runs BEFORE the DIRECTOR-bypass below on purpose: it must
        // not be skipped, it just happens DIRECTOR always satisfies it anyway.
        DocumentStatus status = doc.getStatus();
        if (status == DocumentStatus.PENDING_APPROVAL || status == DocumentStatus.REJECTED) {
            return isDirector || isOwner;
        }

        if (isDirector) {
            return true;
        }
        if (AccessLevel.PUBLIC.equals(doc.getAccessLevel())) {
            return true;
        }

        if (AccessLevel.DEPARTMENT.equals(doc.getAccessLevel())) {
            if (user.getDepartment() == null) return false;
            return doc.getDepartments().stream()
                    .anyMatch(d -> d.getId().equals(user.getDepartment().getId()));
        }
        if (AccessLevel.PROJECT.equals(doc.getAccessLevel())) {
            return doc.getProjects().stream()
                    .anyMatch(p -> projectMemberRepository.existsByProjectAndUser(p, user));
        }

        return false;
    }

    public Filter.Expression buildVectorFilter(User user) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        
        if (user == null) {
            return b.eq("access_level", "PUBLIC").build();
        }

        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        
        if (RoleConstants.ROLE_DIRECTOR.equals(roleCode)) {
            return null;
        }
        List<Long> projectLongIds = projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(pm -> pm.getProject().getId())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        FilterExpressionBuilder.Op combined = b.eq("access_level", "PUBLIC");

        if (user.getDepartment() != null) {
            // "department_ids" là payload dạng mảng ở Qdrant (1 document có thể thuộc
            // nhiều phòng ban) — eq() trên field mảng tự kiểm tra "chứa phần tử".
            combined = b.or(combined, b.and(
                b.eq("access_level", "DEPARTMENT"),
                b.eq("department_ids", String.valueOf(user.getDepartment().getId()))
            ));
        }

        if (!projectLongIds.isEmpty()) {
            Object[] projectIdStrings = projectLongIds.stream()
                    .map(String::valueOf)
                    .toArray();
            // "project_ids" cũng là payload dạng mảng — in() kiểm tra giao nhau giữa
            // danh sách project của user và mảng project_ids của document.
            combined = b.or(combined, b.and(
                b.eq("access_level", "PROJECT"),
                b.in("project_ids", projectIdStrings)
            ));
        }

        log.debug("[SECURITY] Generated vector filter for user {}: {}", user.getEmail(), combined);
        return combined.build();
    }

    public boolean canUpload(User user) {
        if (user == null || user.getRole() == null) return false;
        String roleCode = user.getRole().getCode();
        return RoleConstants.ROLE_DIRECTOR.equals(roleCode) || RoleConstants.ROLE_MANAGER.equals(roleCode);
    }

    private boolean isDirector(User user) {
        return user != null && user.getRole() != null &&
            RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
    }

    /**
     * Trang xem chi tiết tài liệu (document_view) hiển thị toàn bộ nội dung gốc,
     * không qua RAG/permission filter theo từng chunk. Theo quyết định business,
     * chỉ Giám đốc và Trưởng phòng được dùng hành động này; Nhân viên vẫn truy cập
     * nội dung tài liệu trong scope của mình qua AI chat (đã có bảo vệ copy) hoặc
     * tải file gốc nếu được phép. Đây là điều kiện role BỔ SUNG, không thay thế
     * canAccessDocument — vẫn phải kiểm tra scope (department/project/ownership)
     * riêng, không suy luận role = quyền truy cập tài liệu.
     */
    public boolean canViewDocumentDetail(User user) {
        if (user == null || user.getRole() == null) return false;
        String roleCode = user.getRole().getCode();
        return RoleConstants.ROLE_DIRECTOR.equals(roleCode) || RoleConstants.ROLE_MANAGER.equals(roleCode);
    }
    
    public boolean canManageProjects(User user) {
        if (user == null || user.getRole() == null) return false;
        return RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
    }

    public boolean canManageMembers(User user) {
        if (user == null || user.getRole() == null) return false;
        return RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
    }

    /**
     * Gate cho việc VÀO ĐƯỢC trang danh sách dự án (/projects) -- chỉ cần đã
     * đăng nhập. KHÔNG quyết định dự án nào hiển thị: DIRECTOR thấy tất cả,
     * MANAGER/EMPLOYEE chỉ thấy dự án mình tham gia -- việc lọc đó nằm ở
     * ProjectController/ProjectService (ProjectService.getProjectsForUser),
     * không phải ở đây.
     */
    public boolean canAccessProjectsPage(User user) {
        return user != null && user.getRole() != null;
    }

    /**
     * User chỉ được xem chi tiết một project cụ thể nếu là DIRECTOR hoặc thực
     * sự là thành viên project đó (rule 4.3: PROJECT phải kiểm tra
     * ProjectMembership thật, không suy luận từ role/department).
     */
    public boolean canAccessProject(User user, Project project) {
        if (user == null || user.getRole() == null || project == null) return false;
        if (RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode())) return true;
        return projectMemberRepository.existsByProjectAndUser(project, user);
    }

    /**
     * Leader là khái niệm PER-PROJECT (đánh dấu trên ProjectMember), không
     * phải role hệ thống -- một EMPLOYEE vẫn có thể là leader của 1 dự án cụ
     * thể. Không được suy luận role MANAGER/DIRECTOR = leader.
     */
    public boolean isProjectLeader(User user, Project project) {
        if (user == null || project == null) return false;
        return projectMemberRepository.findByProjectAndUser(project, user)
                .map(ProjectMember::isLeader)
                .orElse(false);
    }

    /**
     * Sửa mô tả / trạng thái / hồ sơ tài liệu của MỘT dự án cụ thể: DIRECTOR
     * (toàn quyền) hoặc leader của chính dự án đó (quyền hẹp hơn, được kiểm
     * tra chi tiết ở ProjectService theo từng field/action).
     */
    public boolean canUpdateProject(User user, Project project) {
        return canManageProjects(user) || isProjectLeader(user, project);
    }

    /**
     * Upload tài liệu PROJECT-scope vào MỘT dự án cụ thể: chỉ DIRECTOR (quyền
     * toàn hệ thống, rule 3.1 WORKING_RULES) hoặc leader của CHÍNH dự án đó
     * (per-project, có thể là EMPLOYEE). Business quyết định: một MANAGER chỉ
     * là thành viên thường (không phải leader) KHÔNG còn được upload vào dự
     * án đó chỉ vì role MANAGER -- thành viên còn lại truy cập tài liệu qua
     * AI chat. canUpload() (Director/Manager) không dùng trực tiếp ở đây nữa;
     * nó vẫn giữ nguyên và áp dụng cho DEPARTMENT/PUBLIC upload ở nơi khác.
     */
    public boolean canUploadToProject(User user, Project project) {
        return isDirector(user) || isProjectLeader(user, project);
    }

    /**
     * Xem/tải NỘI DUNG FILE GỐC của tài liệu (vd. GET /api/documents/{id}/viewer)
     * -- khác với canAccessDocument, vốn chỉ xác định phạm vi truy cập
     * metadata/chat. Theo quyết định business (canViewDocumentDetail):
     * Director/Manager luôn được xem file gốc. Với tài liệu PROJECT-scope,
     * leader của CHÍNH dự án đó cũng được xem file gốc (họ quản lý tài liệu
     * dự án mình phụ trách, kể cả khi leader là EMPLOYEE). Thành viên dự án
     * không phải leader (EMPLOYEE lẫn MANAGER không phải leader) KHÔNG được
     * xem file gốc trực tiếp -- chỉ truy cập nội dung qua AI chat. Đây là
     * điều kiện BỔ SUNG, phải gọi SAU khi canAccessDocument đã pass, không
     * thay thế scope check đó.
     */
    public boolean canViewRawDocument(User user, Document doc) {
        if (canViewDocumentDetail(user)) return true;
        if (doc == null || !AccessLevel.PROJECT.equals(doc.getAccessLevel())) return false;
        return doc.getProjects().stream().anyMatch(p -> isProjectLeader(user, p));
    }
}
