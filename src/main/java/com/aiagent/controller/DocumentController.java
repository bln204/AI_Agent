package com.aiagent.controller;

import com.aiagent.model.Document;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.service.DocumentService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Paths;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final com.aiagent.repository.ProjectMemberRepository projectMemberRepository;
    private final com.aiagent.repository.ProjectRepository projectRepository;
    private final com.aiagent.service.DocumentAccessService documentAccessService;

    @GetMapping("/documents")
    public String listDocuments(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (user == null) return "redirect:/login";
        
        if (size <= 0) size = 10;
        if (page < 0) page = 0;
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Document> documentPage = documentService.getAccessibleDocumentsPaginated(user, keyword, pageable);
        
        model.addAttribute("documents", documentPage.getContent());
        model.addAttribute("documentPage", documentPage);
        model.addAttribute("keyword", keyword);
        model.addAttribute("pageSize", size);
        model.addAttribute("currentUser", user);
        return "documents";
    }

    @GetMapping("/documents/upload")
    public String uploadPage(Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (user == null) return "redirect:/login";
        
        if (!documentAccessService.canUpload(user)) {
            redirectAttributes.addFlashAttribute("error", "Bạn không có quyền truy cập trang tải lên tài liệu.");
            return "redirect:/documents";
        }

        model.addAttribute("currentUser", user);
        model.addAttribute("accessLevels", com.aiagent.model.AccessLevel.values());
        
        String roleCode = user.getRole().getCode();
        if (RoleConstants.ROLE_MANAGER.equals(roleCode)) {
            model.addAttribute("departments", List.of(user.getDepartment()));
            model.addAttribute("projects", projectMemberRepository.findByUser(user).stream()
                    .filter(pm -> pm.isActive())
                    .map(pm -> pm.getProject())
                    .toList());
        } else if (RoleConstants.ROLE_DIRECTOR.equals(roleCode)) {
            model.addAttribute("departments", departmentRepository.findByActiveTrue());
            model.addAttribute("projects", projectRepository.findAll());
        }
        
        return "document_upload";
    }

    @PostMapping("/documents/upload")
    public String handleUpload(Authentication authentication,
                               @RequestParam("title") String title,
                               @RequestParam(value = "content", required = false) String content,
                               @RequestParam(value = "departmentIds", required = false) List<Long> departmentIds,
                               @RequestParam(value = "projectIds", required = false) List<Long> projectIds,
                               @RequestParam("accessLevel") com.aiagent.model.AccessLevel accessLevel,
                               @RequestParam(value = "decision", required = false) String decision,
                               @RequestParam(value = "classification", defaultValue = "OTHER") com.aiagent.model.DocumentClassification classification,
                               @RequestParam(value = "projectName", required = false) String projectName,
                               @RequestParam(value = "description", required = false) String description,
                               @RequestParam(value = "internalSource", defaultValue = "true") boolean internalSource,
                               @RequestParam(value = "file", required = false) MultipartFile file,
                               RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        if (user == null) return "redirect:/login";
        
        if (!documentAccessService.canUpload(user)) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: Bạn không có quyền tải lên tài liệu.");
            return "redirect:/documents";
        }

        try {
            documentService.uploadDocument(title, content, departmentIds, projectIds, accessLevel, decision, classification, projectName, description, internalSource, file, user);
            redirectAttributes.addFlashAttribute("success", "Tải tài liệu lên thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/documents";
    }

    @GetMapping("/documents/{id}")
    public String viewDocument(@PathVariable Long id, Authentication authentication, Model model) {
        User user = resolveUser(authentication);
        Document doc = documentService.getDocument(id);

        if (user == null || doc == null) {
             return "redirect:/login";
        }

        if (!documentAccessService.canAccessDocument(user, doc)) {
            return "redirect:/documents";
        }
        
        model.addAttribute("document", doc);
        model.addAttribute("currentUser", user);
        return "document_view";
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id, Authentication authentication) {
        Document doc = documentService.getDocument(id);

        if (doc == null || doc.getFilePath() == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            Resource resource = new UrlResource(Paths.get(doc.getFilePath()).toUri());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/documents/{id}/delete")
    public String deleteDocument(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        User user = resolveUser(authentication);
        try {
            documentService.deleteDocument(id, user);
            redirectAttributes.addFlashAttribute("success", "Đã xoá tài liệu thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi khi xoá tài liệu: " + e.getMessage());
        }
        return "redirect:/documents";
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null) return null;
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
