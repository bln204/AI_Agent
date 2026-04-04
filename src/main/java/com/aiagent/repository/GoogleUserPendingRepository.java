package com.aiagent.repository;

import com.aiagent.model.GoogleUserPending;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface GoogleUserPendingRepository extends JpaRepository<GoogleUserPending, Long> {
    Optional<GoogleUserPending> findByEmail(String email);
}
