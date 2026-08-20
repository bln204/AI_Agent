package com.aiagent.service;

import com.aiagent.model.Document;
import com.aiagent.model.Notification;
import com.aiagent.model.Project;
import com.aiagent.model.User;
import com.aiagent.repository.NotificationRepository;
import com.aiagent.repository.UserRepository;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DB-persisted notifications, polled by the frontend (option A from the
 * approved plan -- no WebSocket/SSE/message broker: this is a single-instance
 * deployment per docker-compose and the existing document-viewer-status.js
 * polling pattern already covers this style of "check back periodically" UX).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * Fired from DocumentService.uploadDocument, in the SAME transaction as
     * the upload itself, right after a MANAGER's document is persisted as
     * PENDING_APPROVAL -- if the upload transaction rolls back, these rows
     * roll back with it; if it commits, the notifications commit with it.
     */
    @Transactional
    public void notifyDirectorsOfPendingDocument(Document doc) {
        List<User> directors = userRepository.findByRole_Code(RoleConstants.ROLE_DIRECTOR);
        if (directors.isEmpty()) {
            log.warn("[NOTIFICATION] No DIRECTOR account found to notify about pending document {}.", doc.getId());
            return;
        }
        String uploaderName = doc.getUploadedBy() != null ? doc.getUploadedBy().getUsername() : "?";
        String message = "Tài liệu \"" + doc.getTitle() + "\" do " + uploaderName + " tải lên đang chờ bạn duyệt.";

        for (User director : directors) {
            Notification n = new Notification();
            n.setRecipient(director);
            n.setDocumentId(doc.getId());
            n.setMessage(message);
            notificationRepository.save(n);
        }
    }

    /**
     * Fired from DocumentService.approveDocument/rejectDocument so the
     * uploader learns the outcome of their submission.
     */
    @Transactional
    public void notifyUploaderOfDecision(Document doc, boolean approved) {
        if (doc.getUploadedBy() == null) {
            return;
        }
        String message = approved
                ? "Tài liệu \"" + doc.getTitle() + "\" của bạn đã được Giám đốc duyệt."
                : "Tài liệu \"" + doc.getTitle() + "\" của bạn đã bị Giám đốc từ chối.";

        Notification n = new Notification();
        n.setRecipient(doc.getUploadedBy());
        n.setDocumentId(doc.getId());
        n.setMessage(message);
        notificationRepository.save(n);
    }

    /**
     * Fired from ProjectService.updateStatus khi Leader (không phải Director)
     * đề xuất gia hạn dự án -- cùng transaction với việc lưu pendingExtensionDate,
     * cùng mẫu notifyDirectorsOfPendingDocument.
     */
    @Transactional
    public void notifyDirectorsOfPendingExtension(Project project) {
        List<User> directors = userRepository.findByRole_Code(RoleConstants.ROLE_DIRECTOR);
        if (directors.isEmpty()) {
            log.warn("[NOTIFICATION] No DIRECTOR account found to notify about pending extension for project {}.", project.getId());
            return;
        }
        String requesterName = project.getExtensionRequestedBy() != null ? project.getExtensionRequestedBy().getUsername() : "?";
        String message = "Dự án \"" + project.getName() + "\" có yêu cầu gia hạn từ " + requesterName + " đang chờ bạn duyệt.";

        for (User director : directors) {
            Notification n = new Notification();
            n.setRecipient(director);
            n.setProjectId(project.getId());
            n.setMessage(message);
            notificationRepository.save(n);
        }
    }

    /**
     * Fired from ProjectService.approveExtension/rejectExtension so the Leader
     * who requested the extension learns the outcome.
     */
    @Transactional
    public void notifyLeaderOfExtensionDecision(Project project, User leader, boolean approved) {
        if (leader == null) {
            return;
        }
        String message = approved
                ? "Yêu cầu gia hạn dự án \"" + project.getName() + "\" của bạn đã được Giám đốc duyệt."
                : "Yêu cầu gia hạn dự án \"" + project.getName() + "\" của bạn đã bị Giám đốc từ chối.";

        Notification n = new Notification();
        n.setRecipient(leader);
        n.setProjectId(project.getId());
        n.setMessage(message);
        notificationRepository.save(n);
    }

    public Page<Notification> getForUser(User user, Pageable pageable) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), pageable);
    }

    public long countUnread(User user) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(user.getId());
    }

    @Transactional
    public void markRead(Long notificationId, User user) {
        Notification n = notificationRepository.findByIdAndRecipientId(notificationId, user.getId())
                .orElseThrow(() -> new SecurityException("Không tìm thấy thông báo."));
        n.setRead(true);
        notificationRepository.save(n);
    }
}
