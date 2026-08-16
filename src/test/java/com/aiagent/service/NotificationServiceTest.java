package com.aiagent.service;

import com.aiagent.model.Document;
import com.aiagent.model.Notification;
import com.aiagent.model.Role;
import com.aiagent.model.User;
import com.aiagent.repository.NotificationRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.RoleConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the notification fan-out (decision #8, Phase 6): every DIRECTOR
 * gets one row when a document enters PENDING_APPROVAL, and the uploader
 * gets one row when a DIRECTOR decides on it. Also the IDOR guard on
 * markRead (a notification can only be marked read by its own recipient).
 */
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private UserRepository userRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(notificationRepository, userRepository);
    }

    private User director(Long id) {
        Role role = new Role();
        role.setCode(RoleConstants.ROLE_DIRECTOR);
        User u = new User();
        u.setId(id);
        u.setUsername("director" + id);
        u.setRole(role);
        return u;
    }

    private Document pendingDoc() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("Bao cao thang 1");
        User uploader = new User();
        uploader.setId(50L);
        uploader.setUsername("manager1");
        doc.setUploadedBy(uploader);
        return doc;
    }

    @Test
    void notifyDirectorsOfPendingDocument_createsOneNotificationPerDirector() {
        when(userRepository.findByRole_Code(RoleConstants.ROLE_DIRECTOR))
                .thenReturn(List.of(director(1L), director(2L)));

        service.notifyDirectorsOfPendingDocument(pendingDoc());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        assertEquals(1L, saved.get(0).getRecipient().getId());
        assertEquals(2L, saved.get(1).getRecipient().getId());
        assertEquals(1L, saved.get(0).getDocumentId());
    }

    @Test
    void notifyDirectorsOfPendingDocument_noDirectorAccount_doesNotThrow() {
        when(userRepository.findByRole_Code(RoleConstants.ROLE_DIRECTOR)).thenReturn(List.of());

        service.notifyDirectorsOfPendingDocument(pendingDoc());

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyUploaderOfDecision_approved_notifiesUploaderOnly() {
        Document doc = pendingDoc();

        service.notifyUploaderOfDecision(doc, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        assertEquals(50L, captor.getValue().getRecipient().getId());
    }

    @Test
    void markRead_wrongRecipient_throwsSecurityException() {
        when(notificationRepository.findByIdAndRecipientId(1L, 999L)).thenReturn(Optional.empty());

        User attacker = new User();
        attacker.setId(999L);

        assertThrows(SecurityException.class, () -> service.markRead(1L, attacker));
    }
}
