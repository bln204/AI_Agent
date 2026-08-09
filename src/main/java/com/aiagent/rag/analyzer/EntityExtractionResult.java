package com.aiagent.rag.analyzer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EntityExtractionResult {
    private List<DetectedEntity> entities;
    private String normalizedQuestion;
}
