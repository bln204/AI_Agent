package com.aiagent.repository;

import com.aiagent.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleId(String googleId);

    java.util.List<User> findByStatus(String status);

    // Used by NotificationService to fan out "document pending approval"
    // notifications to every DIRECTOR account.
    java.util.List<User> findByRole_Code(String roleCode);
}

