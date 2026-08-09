package com.aiagent.controller;

import com.aiagent.model.Project;
import com.aiagent.model.User;
import com.aiagent.service.ProjectService;
import com.aiagent.service.DocumentAccessService;
import com.aiagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final DocumentAccessService documentAccessService;
    private final UserRepository userRepository;

    @GetMapping
    public String listProjects(Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (user == null) return "redirect:/login";

        if (!documentAccessService.canAccessProjectManagement(user)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập trang quản lý dự án.");
            return "redirect:/dashboard";
        }

        List<Project> projects;
        if (com.aiagent.util.RoleConstants.ROLE_DIRECTOR.equals(user.getRole().getCode())) {
            projects = projectService.getAllProjects();
        } else {
            projects = projectService.getProjectsForUser(user);
            if (com.aiagent.util.RoleConstants.ROLE_MANAGER.equals(user.getRole().getCode())) {
                projects = projectService.getAllProjects();
            }
        }

        model.addAttribute("projects", projects);
        model.addAttribute("canManage", documentAccessService.canManageProjects(user));
        return "projects";
    }

    @PostMapping("/create")
    public String createProject(Authentication authentication, 
                                @RequestParam("name") String name,
                                @RequestParam("description") String description,
                                RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (!documentAccessService.canManageProjects(user)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền tạo dự án.");
            return "redirect:/projects";
        }

        projectService.createProject(name, description);
        redirectAttributes.addFlashAttribute("success", "Tạo dự án thành công!");
        return "redirect:/projects";
    }

    @PostMapping("/{id}/update")
    public String updateProject(Authentication authentication,
                                @PathVariable Long id,
                                @RequestParam("name") String name,
                                @RequestParam("description") String description,
                                @RequestParam(value = "active", defaultValue = "false") boolean active,
                                RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (!documentAccessService.canManageProjects(user)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền chỉnh sửa dự án.");
            return "redirect:/projects";
        }

        try {
            projectService.updateProject(id, name, description, active);
            redirectAttributes.addFlashAttribute("success", "Cập nhật dự án thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects";
    }

    @PostMapping("/delete/{id}")
    public String deleteProject(Authentication authentication, @PathVariable Long id, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (!documentAccessService.canManageProjects(user)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền xoá dự án.");
            return "redirect:/projects";
        }

        try {
            projectService.deleteProject(id);
            redirectAttributes.addFlashAttribute("success", "Xoá dự án thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects";
    }

    @GetMapping("/{id}/members")
    public String listMembers(@PathVariable Long id, Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (!documentAccessService.canAccessProjectManagement(user)) {
             return "redirect:/dashboard";
        }

        Project project = projectService.getProjectById(id)
                .orElseThrow(() -> new RuntimeException("Dự án không tồn tại"));

        if (!documentAccessService.canAccessProject(user, project)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập dự án này.");
            return "redirect:/projects";
        }

        model.addAttribute("project", project);
        model.addAttribute("members", projectService.getProjectMembers(project));
        model.addAttribute("allUsers", userRepository.findAll()); // Simple list for adding members
        model.addAttribute("canManageMembers", documentAccessService.canManageMembers(user));
        
        return "project_members";
    }

    @PostMapping("/{id}/members/add")
    public String addMember(@PathVariable Long id, @RequestParam("userId") Long userId, 
                            Authentication authentication, RedirectAttributes redirectAttributes) {
        User currentUser = resolveUser(authentication);
        if (!documentAccessService.canManageMembers(currentUser)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền quản lý thành viên.");
            return "redirect:/projects/" + id + "/members";
        }

        Project project = projectService.getProjectById(id).orElseThrow();
        User userToAdd = userRepository.findById(userId).orElseThrow();
        
        projectService.addMember(project, userToAdd);
        redirectAttributes.addFlashAttribute("success", "Đã thêm thành viên: " + userToAdd.getUsername());
        return "redirect:/projects/" + id + "/members";
    }

    @PostMapping("/{id}/members/remove")
    public String removeMember(@PathVariable Long id, @RequestParam("userId") Long userId, 
                               Authentication authentication, RedirectAttributes redirectAttributes) {
        User currentUser = resolveUser(authentication);
        if (!documentAccessService.canManageMembers(currentUser)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền quản lý thành viên.");
            return "redirect:/projects/" + id + "/members";
        }

        Project project = projectService.getProjectById(id).orElseThrow();
        User userToRemove = userRepository.findById(userId).orElseThrow();
        
        projectService.removeMember(project, userToRemove);
        redirectAttributes.addFlashAttribute("success", "Đã xoá thành viên: " + userToRemove.getUsername());
        return "redirect:/projects/" + id + "/members";
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        String email = null;
        if (principal instanceof OAuth2User oAuth2User) {
            email = oAuth2User.getAttribute("email");
        } else if (principal instanceof UserDetails userDetails) {
            email = userDetails.getUsername();
        }
        if (email == null) return null;
        return userRepository.findByEmail(email).orElse(null);
    }
}
