package com.aiagent.rag.analyzer;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CandidateMatch {
    private String value;
    private EntityType type;
    private double score;
    private String source;
}
