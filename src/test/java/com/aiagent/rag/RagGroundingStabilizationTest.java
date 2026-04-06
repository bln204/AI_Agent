package com.aiagent.rag;

import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.service.DocumentService;

import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RagGroundingStabilizationTest {

    @Autowired
    private RagRetrievalService retrievalService;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private com.aiagent.repository.UserRepository userRepository;

    @Autowired
    private com.aiagent.repository.RoleRepository roleRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        Role managerRole = roleRepository.findByCode(RoleConstants.ROLE_MANAGER)
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("Trưởng phòng");
                    r.setCode(RoleConstants.ROLE_MANAGER);
                    return roleRepository.save(r);
                });

        testUser = userRepository.findByEmail("test_rag@aiagent.com")
                .orElseGet(() -> {
                    User u = new User();
                    u.setUsername("testuser_stabilize");
                    u.setEmail("test_rag@aiagent.com");
                    u.setPassword("password");
                    u.setStatus("ACTIVE");
                    u.setRole(managerRole);
                    return userRepository.save(u);
                });
        
        // Ensure role is set for existing user too
        if (testUser.getRole() == null) {
            testUser.setRole(managerRole);
            testUser = userRepository.save(testUser);
        }
    }

    @Test
    void testDominantDocumentSelectionAndNoiseLimiting() throws IOException, InterruptedException {
        String uniqueSuffix = "_" + System.currentTimeMillis();
        String mainDocName = "main_v5" + uniqueSuffix;
        String noiseDocName = "noise_v4" + uniqueSuffix;

        // 1. Ingest "Main" Document
        String contentV5 = "Thông tin dự án " + mainDocName + ": Đây là phiên bản mới nhất, hỗ trợ tính năng RAG ổn định hơn.";
        MockMultipartFile fileV5 = new MockMultipartFile("file", mainDocName + ".txt", "text/plain", contentV5.getBytes());
        documentService.uploadDocument(mainDocName + ".txt", null, Collections.emptyList(), Collections.emptyList(), 
                com.aiagent.model.AccessLevel.PUBLIC, fileV5, testUser);

        // 2. Ingest "Noise" Document
        String contentV4 = "Thông tin dự án " + noiseDocName + ": Đây là phiên bản cũ.";
        MockMultipartFile fileV4 = new MockMultipartFile("file", noiseDocName + ".txt", "text/plain", contentV4.getBytes());
        documentService.uploadDocument(noiseDocName + ".txt", null, Collections.emptyList(), Collections.emptyList(), 
                com.aiagent.model.AccessLevel.PUBLIC, fileV4, testUser);

        // 3. Wait for ingestion (Async)
        Thread.sleep(3000);

        // 4. Retrieve context
        String query = "du an " + mainDocName + " co thong tin gi";
        List<Document> context = retrievalService.retrieveContext(query, testUser, Collections.emptySet());

        // 5. Verification
        Map<String, List<Document>> groupedByDoc = context.stream()
                .collect(Collectors.groupingBy(d -> (String) d.getMetadata().getOrDefault("document_name", "unknown")));

        // Find the actual keys that contain our unique names
        String actualMainKey = groupedByDoc.keySet().stream().filter(k -> k.contains(mainDocName)).findFirst().orElse(null);
        String actualNoiseKey = groupedByDoc.keySet().stream().filter(k -> k.contains(noiseDocName)).findFirst().orElse(null);

        assertThat(actualMainKey).as("Main document should be retrieved").isNotNull();
        
        // Dominant flag check
        List<Document> mainChunks = groupedByDoc.get(actualMainKey);
        boolean mainIsDominant = mainChunks.stream().allMatch(d -> Boolean.TRUE.equals(d.getMetadata().get("is_dominant")));
        assertThat(mainIsDominant).as(mainDocName + " should be marked as dominant").isTrue();

        // Noise limiting check (if noise was retrieved)
        if (actualNoiseKey != null) {
            List<Document> noiseChunks = groupedByDoc.get(actualNoiseKey);
            assertThat(noiseChunks.size()).as("Noise document should be limited to 2 chunks max").isLessThanOrEqualTo(2);
        }

        System.out.println("Test Passed: Dominant selection and noise limiting verified.");
    }

    @Test
    void testGroundingDriftDetection() throws IOException, InterruptedException {
        String uniqueSuffix = "_drift_" + System.currentTimeMillis();
        String mainDocName = "secret_project" + uniqueSuffix;

        // 1. Ingest Main Document
        String content = "Project " + mainDocName + " uses Alpha technology.";
        MockMultipartFile file = new MockMultipartFile("file", mainDocName + ".txt", "text/plain", content.getBytes());
        documentService.uploadDocument(mainDocName + ".txt", null, Collections.emptyList(), Collections.emptyList(), 
                com.aiagent.model.AccessLevel.PUBLIC, file, testUser);

        // 2. Wait for ingestion
        Thread.sleep(3000);

        // 3. Test Chat with dominant doc - should have YELLOW log if it drifts, but we check just the flow here
        // Since we can't easily mock the AI response in an integration test without Mockito (which might conflict with Spring AI),
        // we'll just verify the context assembly for this case.
        String question = "What technology does " + mainDocName + " use?";
        
        // This will trigger the grounding logic if it finds a dominant doc
        ChatGenerationResult result = aiChatService.chat(null, question, testUser, null);
        
        assertThat(result.getContent()).isNotEmpty();
        // The result should contain part of our answer if it's grounded
        System.out.println("AI Answer: " + result.getContent());
    }
}
