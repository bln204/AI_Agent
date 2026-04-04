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
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
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

        testUser = new User();
        testUser.setUsername("testuser_rag");
        testUser.setEmail("test_rag@aiagent.com");
        testUser.setPassword("password");
        testUser.setStatus("ACTIVE");
        testUser.setDepartment(hrDept);
        testUser.setRole(role);
        testUser = userRepository.save(testUser);
    }

    @Test
    void testIngestAndChat() throws IOException {
        String sampleContent = "Nội quy công ty AI Agent: " +
                "- Lương tháng 13 sẽ được chi trả vào ngày 25/12 hàng năm. " +
                "- Nhân viên được nghỉ phép 15 ngày/năm. " +
                "- Văn phòng mở cửa từ 8:00 đến 18:00.";

        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "rules.txt",
                "text/plain",
                sampleContent.getBytes());

        logInferred("Ingesting rules.txt...");

        // SỬA Ở ĐÂY: Dùng department ID (list)
        documentService.uploadDocument(
                "rules.txt", 
                null, 
                java.util.List.of(hrDept.getId()), 
                java.util.Collections.emptyList(), 
                com.aiagent.model.AccessLevel.PUBLIC, 
                mockFile, 
                testUser);

        // 4. Perform a chat query
        String question = "Lương tháng 13 được trả khi nào?";
        logInferred("Asking: " + question);
        String response = aiChatService.chat(1L, question, testUser.getId(), null);

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