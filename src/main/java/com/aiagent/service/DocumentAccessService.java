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
public class DocumentAccessService {

    private final ProjectMemberRepository projectMemberRepository;

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

        // 4. DEPARTMENT: User must have a department and it must match
        if (AccessLevel.DEPARTMENT.equals(doc.getAccessLevel())) {
            if (user.getDepartment() == null) return false;
            return doc.getDepartments().stream()
                    .anyMatch(d -> d.getId().equals(user.getDepartment().getId()));
        }

        // 5. PROJECT: User must be an active member of one of the document's projects
        if (AccessLevel.PROJECT.equals(doc.getAccessLevel())) {
            return doc.getProjects().stream()
                    .anyMatch(p -> projectMemberRepository.existsByProjectAndUser(p, user));
        }

        return false;
    }

    public Filter.Expression buildVectorFilter(User user) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        
        // 1. GUEST/NULL sees only PUBLIC
        if (user == null) {
            return b.eq("access_level", "PUBLIC").build();
        }

        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        
        // 2. DIRECTOR sees everything (no filter)
        if (RoleConstants.ROLE_DIRECTOR.equals(roleCode)) {
            return null;
        }

        // 3. Collect accessible scopes
        List<Long> projectLongIds = projectMemberRepository.findByUser(user).stream()
                .filter(ProjectMember::isActive)
                .map(pm -> pm.getProject().getId())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        // Build the OR chain
        // Start with PUBLIC
        FilterExpressionBuilder.Op combined = b.eq("access_level", "PUBLIC");

        // Add PRIVATE (Owned by user)
        combined = b.or(combined, b.and(
            b.eq("access_level", "PRIVATE"),
            b.eq("uploader_id", String.valueOf(user.getId()))
        ));

        // Add DEPARTMENT
        if (user.getDepartment() != null) {
            combined = b.or(combined, b.and(
                b.eq("access_level", "DEPARTMENT"),
                b.eq("department_id", String.valueOf(user.getDepartment().getId()))
            ));
        }

        // Add PROJECT
        if (!projectLongIds.isEmpty()) {
            Object[] projectIdStrings = projectLongIds.stream()
                    .map(String::valueOf)
                    .toArray();
            combined = b.or(combined, b.and(
                b.eq("access_level", "PROJECT"),
                b.in("project_id", projectIdStrings)
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
