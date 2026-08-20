package com.aiagent.controller;

import com.aiagent.model.AccessLevel;
import com.aiagent.model.Document;
import com.aiagent.model.Project;
import com.aiagent.model.ProjectStatus;
import com.aiagent.model.User;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.DocumentAccessService;
import com.aiagent.service.DocumentService;
import com.aiagent.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final DocumentAccessService documentAccessService;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;

    @GetMapping
    public String listProjects(Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (user == null) return "redirect:/login";

        if (!documentAccessService.canAccessProjectsPage(user)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập trang quản lý dự án.");
            return "redirect:/dashboard";
        }

        boolean canManage = documentAccessService.canManageProjects(user);
        List<Project> projects = canManage ? projectService.getAllProjects() : projectService.getProjectsForUser(user);

        model.addAttribute("projects", projects);
        model.addAttribute("canManage", canManage);
        model.addAttribute("isDirector", canManage);
        if (canManage) {
            model.addAttribute("allUsers", userRepository.findAll());
        }
        return "projects";
    }

    @PostMapping("/create")
    public String createProject(Authentication authentication,
                                @RequestParam("name") String name,
                                @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                @RequestParam("expectedEndDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedEndDate,
                                @RequestParam(value = "projectType", required = false) String projectType,
                                @RequestParam(value = "cost", required = false) BigDecimal cost,
                                @RequestParam(value = "description", required = false) String description,
                                @RequestParam(value = "memberIds", required = false) List<Long> memberIds,
                                @RequestParam(value = "leaderId", required = false) Long leaderId,
                                @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (!documentAccessService.canManageProjects(user)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền tạo dự án.");
            return "redirect:/projects";
        }

        try {
            List<User> members = memberIds != null ? userRepository.findAllById(memberIds) : List.of();
            User leader = leaderId != null ? userRepository.findById(leaderId).orElse(null) : null;

            Project project = projectService.createProject(name, startDate, expectedEndDate, projectType, cost,
                    description, members, leader);

            uploadInitialDocuments(project, files, user);

            redirectAttributes.addFlashAttribute("success", "Tạo dự án thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects";
    }

    @GetMapping("/{id}")
    public String viewProject(@PathVariable Long id, Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (user == null) return "redirect:/login";

        Project project = projectService.getProjectById(id).orElse(null);
        if (project == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy dự án.");
            return "redirect:/projects";
        }
        if (!documentAccessService.canAccessProject(user, project)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập dự án này.");
            return "redirect:/projects";
        }

        boolean canUpdate = documentAccessService.canUpdateProject(user, project);
        boolean canManageMembers = documentAccessService.canManageMembers(user);
        boolean canUploadDocs = documentAccessService.canUploadToProject(user, project);
        boolean canViewDocDetail = documentAccessService.canViewDocumentDetail(user);
        boolean isDirector = documentAccessService.canManageProjects(user);
        boolean frozen = projectService.isFrozen(project);

        List<Document> visibleDocuments = documentRepository.findByProjectId(id).stream()
                .filter(doc -> documentAccessService.canAccessDocument(user, doc))
                .toList();
        List<com.aiagent.model.ProjectMember> members = projectService.getProjectMembers(project);

        model.addAttribute("project", project);
        model.addAttribute("members", members);
        model.addAttribute("documents", visibleDocuments);
        if (canManageMembers) {
            model.addAttribute("allUsers", userRepository.findAll());
            String memberIdsCsv = members.stream().map(m -> String.valueOf(m.getUser().getId()))
                    .reduce((a, b) -> a + "," + b).orElse("");
            model.addAttribute("memberIdsCsv", memberIdsCsv);
        }
        model.addAttribute("canUpdate", canUpdate);
        model.addAttribute("canManageMembers", canManageMembers);
        model.addAttribute("canUploadDocs", canUploadDocs);
        model.addAttribute("canViewDocDetail", canViewDocDetail);
        model.addAttribute("isDirector", isDirector);
        model.addAttribute("frozen", frozen);
        model.addAttribute("effectiveDeadline", projectService.effectiveDeadline(project));

        return "project_view";
    }

    @PostMapping("/{id}/update")
    public String updateDescription(@PathVariable Long id, Authentication authentication,
                                    @RequestParam(value = "description", required = false) String description,
                                    RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        try {
            projectService.updateDescription(id, description, user);
            redirectAttributes.addFlashAttribute("success", "Cập nhật mô tả thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id, Authentication authentication,
                               @RequestParam("status") ProjectStatus status,
                               @RequestParam(value = "extensionDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate extensionDate,
                               RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        try {
            Project updated = projectService.updateStatus(id, status, extensionDate, user);
            String message = status == ProjectStatus.EXTENDED && updated.getPendingExtensionDate() != null
                    ? "Yêu cầu gia hạn đã được gửi, đang chờ Giám đốc duyệt."
                    : "Cập nhật trạng thái thành công!";
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/extension/approve")
    public String approveExtension(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        try {
            projectService.approveExtension(id, user);
            redirectAttributes.addFlashAttribute("success", "Đã duyệt yêu cầu gia hạn.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/extension/reject")
    public String rejectExtension(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        try {
            projectService.rejectExtension(id, user);
            redirectAttributes.addFlashAttribute("success", "Đã từ chối yêu cầu gia hạn.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/reopen")
    public String reopenProject(@PathVariable Long id, Authentication authentication,
                                @RequestParam("extensionDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate extensionDate,
                                RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        try {
            projectService.reopenProject(id, extensionDate, user);
            redirectAttributes.addFlashAttribute("success", "Đã mở lại dự án.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/documents/upload")
    public String uploadProjectDocument(@PathVariable Long id, Authentication authentication,
                                        @RequestParam("title") String title,
                                        @RequestParam(value = "description", required = false) String description,
                                        @RequestParam("file") MultipartFile file,
                                        RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        Project project = projectService.getProjectById(id).orElse(null);
        if (project == null) {
            redirectAttributes.addFlashAttribute("error", "Không tìm thấy dự án.");
            return "redirect:/projects";
        }
        if (!documentAccessService.canUploadToProject(user, project)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền tải tài liệu lên dự án này.");
            return "redirect:/projects/" + id;
        }

        try {
            documentService.uploadDocument(title, null, null, List.of(id), AccessLevel.PROJECT,
                    null, com.aiagent.model.DocumentClassification.OTHER, project.getName(), description,
                    true, file, user);
            redirectAttributes.addFlashAttribute("success", "Đã tải tài liệu lên dự án!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/members/add")
    public String addMember(@PathVariable Long id, @RequestParam("memberIds") List<Long> memberIds,
                            @RequestParam(value = "leaderId", required = false) Long leaderId,
                            Authentication authentication, RedirectAttributes redirectAttributes) {
        User currentUser = resolveUser(authentication);
        if (!documentAccessService.canManageMembers(currentUser)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền quản lý thành viên.");
            return "redirect:/projects/" + id;
        }

        Project project = projectService.getProjectById(id).orElseThrow();
        List<User> usersToAdd = userRepository.findAllById(memberIds);
        for (User userToAdd : usersToAdd) {
            projectService.addMember(project, userToAdd);
        }

        if (leaderId != null) {
            User newLeader = userRepository.findById(leaderId).orElseThrow();
            projectService.setLeader(project, newLeader);
        }

        redirectAttributes.addFlashAttribute("success", "Đã thêm " + usersToAdd.size() + " thành viên.");
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/members/remove")
    public String removeMember(@PathVariable Long id, @RequestParam("userId") Long userId,
                               Authentication authentication, RedirectAttributes redirectAttributes) {
        User currentUser = resolveUser(authentication);
        if (!documentAccessService.canManageMembers(currentUser)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền quản lý thành viên.");
            return "redirect:/projects/" + id;
        }

        Project project = projectService.getProjectById(id).orElseThrow();
        User userToRemove = userRepository.findById(userId).orElseThrow();

        projectService.removeMember(project, userToRemove);
        redirectAttributes.addFlashAttribute("success", "Đã xoá thành viên: " + userToRemove.getUsername());
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/members/set-leader")
    public String setLeader(@PathVariable Long id, @RequestParam("userId") Long userId,
                            Authentication authentication, RedirectAttributes redirectAttributes) {
        User currentUser = resolveUser(authentication);
        if (!documentAccessService.canManageMembers(currentUser)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền quản lý thành viên.");
            return "redirect:/projects/" + id;
        }

        try {
            Project project = projectService.getProjectById(id).orElseThrow();
            User newLeader = userRepository.findById(userId).orElseThrow();
            projectService.setLeader(project, newLeader);
            redirectAttributes.addFlashAttribute("success", "Đã đổi leader dự án: " + newLeader.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/projects/" + id;
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

    /**
     * Hồ sơ tài liệu đính kèm lúc TẠO dự án (field "Hồ sơ tài liệu dự án"
     * trong form Create) -- tái dùng DocumentService.uploadDocument với
     * accessLevel=PROJECT, KHÔNG tạo pipeline lưu file riêng. Director luôn
     * pass canUpload() nên không cần ngoại lệ leader ở bước tạo (chỉ người
     * tạo dự án -- luôn là Director -- mới đính kèm được lúc này).
     */
    private void uploadInitialDocuments(Project project, List<MultipartFile> files, User uploader) throws java.io.IOException {
        if (files == null) return;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            documentService.uploadDocument(file.getOriginalFilename(), null, null, List.of(project.getId()),
                    AccessLevel.PROJECT, null, com.aiagent.model.DocumentClassification.OTHER,
                    project.getName(), null, true, file, uploader);
        }
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
