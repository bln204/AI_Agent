package com.aiagent.repository;

import com.aiagent.model.DecisionNumberSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DecisionNumberSequenceRepository extends JpaRepository<DecisionNumberSequence, String> {
}
