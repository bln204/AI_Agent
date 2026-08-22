package com.aiagent.repository;

import com.aiagent.model.PasswordResetOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {
    Optional<PasswordResetOtp> findTopByEmailAndUsedFalseOrderByCreatedAtDesc(String email);

    void deleteByEmail(String email);
}
