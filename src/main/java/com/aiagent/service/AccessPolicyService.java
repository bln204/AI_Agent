package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
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
public class AccessPolicyService {

    private final ProjectMemberRepository projectMemberRepository;

    public record SecurityScope(
        Long userId,
        String roleCode,
        Long departmentId,
        List<Long> projectIds
    ) {}

    public SecurityScope getSecurityScope(User user) {
        if (user == null) {
            return new SecurityScope(-1L, RoleConstants.ROLE_GUEST, -1L, List.of());
        }
        
        List<Long> projectIds = projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(pm -> pm.getProject().getId())
                .collect(Collectors.toList());
                
        return new SecurityScope(
            user.getId(),
            user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST,
            user.getDepartment() != null ? user.getDepartment().getId() : -1L,
            projectIds
        );
    }

    public Filter.Expression buildVectorFilter(User user) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op op = buildVectorFilterOp(user, b);
        return op != null ? op.build() : null;
    }

    public FilterExpressionBuilder.Op buildVectorFilterOp(User user, FilterExpressionBuilder b) {
        if (user == null) {
            return b.eq("access_level", "PUBLIC");
        }

        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        
        // DIRECTOR sees everything
        if (RoleConstants.ROLE_DIRECTOR.equals(roleCode)) {
            return null;
        }

        // Standardize IDs from ProjectMember
        List<Long> projectLongIds = projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(pm -> pm.getProject().getId())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        // Multi-project support (IN operator)
        // 1. PUBLIC documents
        FilterExpressionBuilder.Op pOp = b.eq("access_level", "PUBLIC");

        // 2. PRIVATE documents (Owned by me)
        FilterExpressionBuilder.Op privateOp = b.and(
            b.eq("access_level", "PRIVATE"),
            b.eq("uploader_id", String.valueOf(user.getId()))
        );

        // 3. PROJECT documents
        FilterExpressionBuilder.Op projectScopeOp = null;
        if (!projectLongIds.isEmpty()) {
            Object[] projectIdStrings = projectLongIds.stream()
                    .map(String::valueOf)
                    .toArray();
            projectScopeOp = b.and(
                b.eq("access_level", "PROJECT"),
                b.in("project_id", projectIdStrings)
            );
        }

        // 4. DEPARTMENT documents
        FilterExpressionBuilder.Op deptScopeOp = null;
        if (user.getDepartment() != null) {
            deptScopeOp = b.and(
                b.eq("access_level", "DEPARTMENT"),
                b.eq("department_id", String.valueOf(user.getDepartment().getId()))
            );
        }

        // Combine all using OR (Defense-in-depth Layer 1)
        FilterExpressionBuilder.Op combined = b.or(pOp, privateOp);
        if (projectScopeOp != null) combined = b.or(combined, projectScopeOp);
        if (deptScopeOp != null) combined = b.or(combined, deptScopeOp);

        if (combined == null) {
            log.warn("[RAG-FILTER] No valid combined filter for user: {}. Using DenyAll.", user.getEmail());
            combined = getDenyAllFilter(b);
        }

        log.info("[RAG-FILTER] Final generated context filter: {}", combined);
        return combined;
    }

    /**
     * Fail-closed check: returns a filter that should match NO data if everything else fails.
     */
    private FilterExpressionBuilder.Op getDenyAllFilter(FilterExpressionBuilder b) {
        return b.eq("access_level", "NONE_MATCH_SECURITY_GUARD");
    }

    public boolean canAccessDocument(User user, Document doc) {
        if (doc == null) return false;
        
        // 1. DIRECTOR sees everything
        if (user != null && user.getRole() != null && 
            RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode())) {
            return true;
        }

        // 2. PUBLIC is accessible to everyone
        if (AccessLevel.PUBLIC.equals(doc.getAccessLevel())) {
            return true;
        }

        if (user == null) return false;

        // 3. PRIVATE: Only uploader (and Director, handled above)
        if (AccessLevel.PRIVATE.equals(doc.getAccessLevel())) {
            return doc.getUploadedBy() != null && doc.getUploadedBy().getId().equals(user.getId());
        }

        // 4. DEPARTMENT: User in document's departments
        if (AccessLevel.DEPARTMENT.equals(doc.getAccessLevel())) {
            if (user.getDepartment() == null) return false;
            return doc.getDepartments().stream()
                    .anyMatch(d -> d.getId().equals(user.getDepartment().getId()));
        }

        // 5. PROJECT: User in one of document's projects
        if (AccessLevel.PROJECT.equals(doc.getAccessLevel())) {
            return doc.getProjects().stream()
                    .anyMatch(p -> projectMemberRepository.existsByProjectAndUser(p, user));
        }

        return false;
    }

    public boolean canUpload(User user) {
        if (user == null || user.getRole() == null) return false;
        String roleCode = user.getRole().getCode();
        return RoleConstants.ROLE_DIRECTOR.equals(roleCode) || RoleConstants.ROLE_MANAGER.equals(roleCode);
    }
    
    public boolean canManageProjects(User user) {
        if (user == null || user.getRole() == null) return false;
        return RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
    }

    public boolean canViewProjects(User user) {
        if (user == null || user.getRole() == null) return false;
        String roleCode = user.getRole().getCode();
        return RoleConstants.ROLE_DIRECTOR.equals(roleCode) || RoleConstants.ROLE_MANAGER.equals(roleCode);
    }

    public boolean canManageMembers(User user) {
        if (user == null || user.getRole() == null) return false;
        return RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode());
    }

    public boolean canAccessProjectManagement(User user) {
        if (user == null || user.getRole() == null) return false;
        String roleCode = user.getRole().getCode();
        return RoleConstants.ROLE_DIRECTOR.equals(roleCode) || RoleConstants.ROLE_MANAGER.equals(roleCode);
    }
}


