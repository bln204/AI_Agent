package com.aiagent.controller;

import com.aiagent.model.Document;
import com.aiagent.model.DocumentClassification;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.DocumentService;
import lombok.RequiredArgsConstructor;
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
public class DocumentApiController {

    private final DocumentService documentService;
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
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<Document>> getAllDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.getDocument(id));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Document>> searchDocuments(@RequestParam("keyword") String keyword) {
        return ResponseEntity.ok(documentRepository.findByNormalizedTitleContaining(keyword));
    }

    @GetMapping("/decision/{smecode}")
    public ResponseEntity<Document> getByDecisionNumber(@PathVariable String smecode) {
        return documentRepository.findByDecisionNumber(smecode)
                .map(ResponseEntity::ok)
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
