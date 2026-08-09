package com.aiagent.controller;

import com.aiagent.model.Document;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.DocumentAccessService;
import com.aiagent.service.DocumentService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


@RestController
@RequestMapping(value = "/api/documents", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
@Slf4j
public class DocumentApiController {

    private final DocumentService documentService;
    private final DocumentAccessService documentAccessService;
    private final UserRepository userRepository;
    private final com.aiagent.repository.DocumentRepository documentRepository;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocument(
            Authentication authentication,
            @RequestParam("title") String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "departmentIds", required = false) List<Long> departmentIds,
            @RequestParam(value = "projectIds", required = false) List<Long> projectIds,
            @RequestParam("accessLevel") com.aiagent.model.AccessLevel accessLevel,
            @RequestParam(value = "decisionNumber", required = false) String decisionNumber,
            @RequestParam("classification") DocumentClassification classification,
            @RequestParam(value = "projectName", required = false) String projectName,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "internalSource", defaultValue = "true") boolean internalSource,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        try {
            Document doc = documentService.uploadDocument(
                    title, content, departmentIds, projectIds, accessLevel, 
                    decisionNumber, classification, projectName, description, internalSource, file, user);
            return ResponseEntity.ok(doc);
        } catch (Exception e) {
            log.error("Document upload failed for user {}", user.getEmail(), e);
            return ResponseEntity.badRequest().body("Không thể tải tài liệu lên. Vui lòng kiểm tra lại tệp.");
        }
    }

    @GetMapping
    public ResponseEntity<List<Document>> getAllDocuments(Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<Document> accessible = documentService
                .getAccessibleDocumentsPaginated(user, null, Pageable.unpaged())
                .getContent();
        return ResponseEntity.ok(accessible);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable Long id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Document doc = documentService.getDocument(id);
        if (!documentAccessService.canAccessDocument(user, doc)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(doc);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Document>> searchDocuments(@RequestParam("keyword") String keyword, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String roleCode = user.getRole() != null ? user.getRole().getCode() : RoleConstants.ROLE_GUEST;
        Long departmentId = user.getDepartment() != null ? user.getDepartment().getId() : null;
        List<Document> results = documentRepository.findCandidateDocuments(keyword, roleCode, user.getId(), departmentId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/decision/{smecode}")
    public ResponseEntity<Document> getByDecisionNumber(@PathVariable String smecode, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return documentRepository.findByDecisionNumber(smecode)
                .map(doc -> {
                    if (!documentAccessService.canAccessDocument(user, doc)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Document>build();
                    }
                    return ResponseEntity.ok(doc);
                })
                .orElse(ResponseEntity.notFound().build());
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
