package com.aiagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "decision_number_sequences")
@Data
@NoArgsConstructor
public class DecisionNumberSequence {
    @Id
    private String prefix = "SME";

    @Column(nullable = false)
    private Long lastSequence = 0L;
}
