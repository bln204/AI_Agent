package com.aiagent.controller;

import com.aiagent.model.User;
import com.aiagent.repository.UserRepository;
import com.aiagent.service.UserService;
import com.aiagent.util.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping(value = "/api/users", produces = "application/json;charset=UTF-8")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers(Authentication authentication) {
        User requester = resolveUser(authentication);
        if (requester == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!isHighLevel(requester)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id, Authentication authentication) {
        User requester = resolveUser(authentication);
        if (requester == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!isHighLevel(requester) && !requester.getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return userService.getUserById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user, Authentication authentication) {
        User requester = resolveUser(authentication);
        if (requester == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!isHighLevel(requester)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        User createdUser = userService.createUser(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id, @RequestBody User userDetails, Authentication authentication) {
        User requester = resolveUser(authentication);
        if (requester == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        boolean highLevel = isHighLevel(requester);
        if (!highLevel && !requester.getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            User updatedUser = userService.updateUser(id, userDetails, highLevel);
            return ResponseEntity.ok(updatedUser);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id, Authentication authentication) {
        User requester = resolveUser(authentication);
        if (requester == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!isHighLevel(requester)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            userService.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<User>> getUsersByStatus(@PathVariable String status, Authentication authentication) {
        User requester = resolveUser(authentication);
        if (requester == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!isHighLevel(requester)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(userService.getUsersByStatus(status));
    }

    private boolean isHighLevel(User user) {
        return user != null && user.getRole() != null && RoleConstants.isHighLevel(user.getRole().getCode());
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

