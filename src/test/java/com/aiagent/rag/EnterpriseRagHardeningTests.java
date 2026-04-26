package com.aiagent.rag;

import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.RoleRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.CacheKeyUtils;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.rag.max-context-chars=500",
        "app.rag.similarity-threshold=0.7",
        "app.rag.top-k=10"
})
public class EnterpriseRagHardeningTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

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

        testUser = userRepository.findByEmail("hardening_test@aiagent.com")
                .orElseGet(() -> {
                    User u = new User();
                    u.setUsername("harden_user");
                    u.setEmail("hardening_test@aiagent.com");
                    u.setPassword("password");
                    u.setStatus("ACTIVE");
                    u.setRole(managerRole);
                    return userRepository.save(u);
                });
    }

    @Test
    void testCacheKeyNormalization() {
        Set<String> docs1 = Set.of("doc_A.pdf", "doc_B.docx", "doc_C.txt");
        Set<String> docs2 = Set.of("doc_C.txt", "doc_A.pdf", "doc_B.docx");

        String key1 = CacheKeyUtils.generateRetrievalKey(
                "Hello World",
                testUser,
                docs1);

        String key2 = CacheKeyUtils.generateRetrievalKey(
                "hello world ",
                testUser,
                docs2);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).contains("q:hello world");
        assertThat(key1).contains("u:" + testUser.getId());
        assertThat(key1).contains("docs:[doc_a.pdf,doc_b.docx,doc_c.txt]");
    }
}
