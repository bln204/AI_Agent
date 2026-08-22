package com.aiagent.repository;

import com.aiagent.model.Document;
import com.aiagent.model.DocumentStatus;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng DocumentRepository#existsByStatusVisibleTo (badge "!" trên menu
 * "Công Văn Hội Sở" cho DIRECTOR/MANAGER) thực sự parse được và trả đúng kết
 * quả theo scope -- Spring Data JPA chỉ validate tên method dẫn xuất lúc tạo
 * bean repository proxy (runtime), không phải lúc compile.
 */
@DataJpaTest
class DocumentRepositoryPendingApprovalBadgeTest {

    @Autowired
    private TestEntityManager em;
    @Autowired
    private DocumentRepository documentRepository;

    private Role role(String code, String name) {
        Role r = new Role();
        r.setCode(code);
        r.setName(name);
        return em.persist(r);
    }

    private User user(String email, Role role) {
        User u = new User();
        u.setUsername(email);
        u.setEmail(email);
        u.setRole(role);
        return em.persist(u);
    }

    private void document(User uploadedBy, DocumentStatus status) {
        Document d = new Document();
        d.setTitle("Doc");
        d.setDocumentUuid(UUID.randomUUID().toString());
        d.setStatus(status);
        d.setUploadedBy(uploadedBy);
        em.persist(d);
    }

    @Test
    void existsByStatusVisibleTo_directorSeesAnyone_managerOnlyOwn() {
        Role directorRole = role("DIRECTOR", "Giám đốc");
        Role managerRole = role("MANAGER", "Trưởng phòng");
        User director = user("director@company.com", directorRole);
        User managerA = user("managerA@company.com", managerRole);
        User managerB = user("managerB@company.com", managerRole);

        document(managerA, DocumentStatus.PENDING_APPROVAL);

        assertTrue(documentRepository.existsByStatusVisibleTo(
                DocumentStatus.PENDING_APPROVAL, "DIRECTOR", director.getId()));
        assertTrue(documentRepository.existsByStatusVisibleTo(
                DocumentStatus.PENDING_APPROVAL, "MANAGER", managerA.getId()));
        assertFalse(documentRepository.existsByStatusVisibleTo(
                DocumentStatus.PENDING_APPROVAL, "MANAGER", managerB.getId()));
    }
}
