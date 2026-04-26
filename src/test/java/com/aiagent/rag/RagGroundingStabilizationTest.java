package com.aiagent.rag;

import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.service.DocumentService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RagGroundingStabilizationTest {

    @Autowired
    private RagService ragService;

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
        
        if (testUser.getRole() == null) {
            testUser.setRole(managerRole);
            testUser = userRepository.save(testUser);
        }
    }

    @Test
    void testBasicRagFlow() throws IOException, InterruptedException {
        String uniqueSuffix = "_" + System.currentTimeMillis();
        String mainDocName = "main_v5" + uniqueSuffix;

        // 1. Ingest "Main" Document
        String contentV5 = "Thông tin dự án " + mainDocName + ": Đây là phiên bản mới nhất, hỗ trợ tính năng RAG ổn định hơn.";
        MockMultipartFile fileV5 = new MockMultipartFile("file", mainDocName + ".txt", "text/plain", contentV5.getBytes());
        
        // Pass 'null' for the new decision parameter
        documentService.uploadDocument(mainDocName + ".txt", "", Collections.emptyList(), Collections.emptyList(), 
                com.aiagent.model.AccessLevel.PUBLIC, null, com.aiagent.model.DocumentClassification.OTHER,
                null, "Test Doc", true, fileV5, testUser);

        // 2. Wait for ingestion (Async)
        Thread.sleep(3000);

        // 3. Process Query
        String question = "du an " + mainDocName + " co thong tin gi";
        String response = ragService.processQuery(question, testUser, null, "");

        // 4. Verification
        assertThat(response).contains("PHẦN 1:");
        assertThat(response).contains("PHẦN 2:");
        assertThat(response).contains(mainDocName);
    }
}
