package com.aiagent.controller;

import com.aiagent.model.Notification;
import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Backs the notification bell in fragments/header.html — client-side polling
 * (decision #8), no WebSocket/SSE.
 */
@RestController
@RequestMapping(value = "/api/notifications", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
@Slf4j
public class NotificationApiController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Page<Notification>> list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();

        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? 10 : size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(notificationService.getForUser(user, pageable));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();

        return ResponseEntity.ok(Map.of("count", notificationService.countUnread(user)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, Authentication authentication) {
        User user = resolveUser(authentication);
        if (user == null) return ResponseEntity.status(401).build();

        try {
            notificationService.markRead(id, user);
            return ResponseEntity.ok().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(404).build();
        }
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
