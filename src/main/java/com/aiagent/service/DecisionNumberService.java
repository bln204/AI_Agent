package com.aiagent.service;

import com.aiagent.model.DecisionNumberSequence;
import com.aiagent.repository.DecisionNumberSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DecisionNumberService {

    private final DecisionNumberSequenceRepository sequenceRepository;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public synchronized String generateNextDecisionNumber() {
        DecisionNumberSequence sequence = sequenceRepository.findById("SME")
                .orElseGet(() -> {
                    DecisionNumberSequence newSeq = new DecisionNumberSequence();
                    newSeq.setPrefix("SME");
                    newSeq.setLastSequence(0L);
                    return sequenceRepository.save(newSeq);
                });

        long nextVal = sequence.getLastSequence() + 1;
        sequence.setLastSequence(nextVal);
        sequenceRepository.save(sequence);

        return String.format("SME-%09d", nextVal);
    }
}
