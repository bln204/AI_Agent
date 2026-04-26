package com.aiagent.rag;

import com.aiagent.model.Department;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.DepartmentRepository;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.DocumentService;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RagIntegrationTest {

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User testUser;
    private Department hrDept;

    @BeforeEach
    void setUp() {
        hrDept = departmentRepository.findByCode("HR")
                .orElseGet(() -> {
                    Department d = new Department();
                    d.setName("Nhân sự");
                    d.setCode("HR");
                    return departmentRepository.save(d);
                });

        Role role = roleRepository.findByCode(RoleConstants.ROLE_MANAGER)
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("Trưởng phòng");
                    r.setCode(RoleConstants.ROLE_MANAGER);
                    return roleRepository.save(r);
                });

        testUser = userRepository.findByEmail("test_rag@aiagent.com")
                .orElseGet(() -> {
                    User u = new User();
                    u.setUsername("testuser_rag");
                    u.setEmail("test_rag@aiagent.com");
                    u.setPassword("password");
                    u.setStatus("ACTIVE");
                    u.setDepartment(hrDept);
                    u.setRole(role);
                    return userRepository.save(u);
                });
    }

    @Test
    void testIngestAndChat() throws IOException {
        String uniqueSuffix = "_" + System.currentTimeMillis();
        String fileName = "rules" + uniqueSuffix + ".txt";
        String sampleContent = "Nội quy công ty AI Agent: " +
                "- Lương tháng 13 sẽ được chi trả vào ngày 25/12 hàng năm. " +
                "- Nhân viên được nghỉ phép 15 ngày/năm. " +
                "- Văn phòng mở cửa từ 8:00 đến 18:00.";

        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                fileName,
                "text/plain",
                sampleContent.getBytes());

        logInferred("Ingesting " + fileName + "...");

        // SỬA Ở ĐÂY: Dùng department ID (list)
        com.aiagent.model.Document uploadedDoc = documentService.uploadDocument(
                fileName, 
                null, 
                java.util.List.of(hrDept.getId()), 
                java.util.Collections.emptyList(), 
                com.aiagent.model.AccessLevel.PUBLIC, 
                null,
                com.aiagent.model.DocumentClassification.OTHER,
                null,
                "Integration Test Doc",
                true,
                mockFile, 
                testUser);

        // [INGESTION-WAIT] documentIngestionService.ingestDocument is @Async. 
        // We must wait for it to finish vectorizing before we can chat.
        try {
            logInferred("Waiting for async ingestion...");
            Thread.sleep(5000); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. Perform a chat query
        String question = "Lương tháng 13 được trả khi nào?";
        logInferred("Asking: " + question);
        ChatGenerationResult result = aiChatService.chat(uploadedDoc.getId(), question, testUser, null);
        String response = result.getContent();

        System.out.println("AI Response: " + response);

        // 5. Verification
        assertThat(response).contains("25/12");
        assertThat(response).isNotEmpty();
        logInferred("Test passed! AI used the context correctly.");
    }

    private void logInferred(String msg) {
        System.out.println("[TEST DEBUG] " + msg);
    }
}