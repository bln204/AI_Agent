package com.aiagent.service;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.User;
import com.aiagent.model.ProjectMember;
import com.aiagent.repository.ProjectMemberRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccessPolicyService {

    private final ProjectMemberRepository projectMemberRepository;

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

        Long deptId = user.getDepartment() != null ? user.getDepartment().getId() : -1L;
        Long userId = user.getId();
        
        // Projects user belongs to
        List<Long> projectIds = projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(pm -> pm.getProject().getId())
                .collect(Collectors.toList());

        // Logic pre-filter:
        // (access_level == PUBLIC)
        // OR (access_level == DEPARTMENT AND department_ids CONTAINS user_dept_id)
        // OR (access_level == PROJECT AND project_ids CONTAINS any of user_project_ids)
        // OR (access_level == PRIVATE AND uploader_id == user_id)
        
        FilterExpressionBuilder.Op publicOp = b.eq("access_level", "PUBLIC");
        
        FilterExpressionBuilder.Op deptOp = b.and(
            b.eq("access_level", "DEPARTMENT"),
            b.in("department_ids", List.of(deptId))
        );

        FilterExpressionBuilder.Op projOp = null;
        if (!projectIds.isEmpty()) {
            projOp = b.and(
                b.eq("access_level", "PROJECT"),
                b.in("project_ids", projectIds)
            );
        }

        FilterExpressionBuilder.Op privateOp = b.and(
            b.eq("access_level", "PRIVATE"),
            b.eq("uploader_id", userId)
        );

        FilterExpressionBuilder.Op combined = b.or(publicOp, deptOp);
        if (projOp != null) {
            combined = b.or(combined, projOp);
        }
        combined = b.or(combined, privateOp);

        return combined;
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


