package com.aiagent.rag.filter;

import com.aiagent.rag.analyzer.DetectedEntity;
import com.aiagent.rag.analyzer.EntityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class MetadataFilterBuilder {

    public Filter.Expression build(List<DetectedEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression combined = null;

        for (DetectedEntity entity : entities) {
            String metadataKey = mapToMetadataKey(entity.getType());
            if (metadataKey == null) continue;

            Filter.Expression current = b.eq(metadataKey, entity.getValue()).build();
            
            if (combined == null) {
                combined = current;
            } else {
                // If multiple entities, we OR them or AND them? 
                // Usually if user specifies multiple things, it's more specific, so AND is better.
                // However, if they are the same type, OR might be better.
                // For Sprint 2, we stick to OR for multiple matches to be safe, or AND for different types.
                // Let's use OR for now to cast a wider but still filtered net.
                combined = new Filter.Expression(Filter.ExpressionType.OR, combined, current);
            }
        }

        if (combined != null) {
            log.info("[FILTER] Generated Metadata Filter: {}", combined);
        }
        
        return combined;
    }

    private String mapToMetadataKey(EntityType type) {
        return switch (type) {
            case PROJECT -> "project_name";
            case DOCUMENT_TITLE -> "document_name"; // Ingestion uses document_name
            case CUSTOMER -> "customer_name";
            case CONTRACT -> "decision_number"; // Ingestion uses decision_number
            case INVOICE -> "invoice_number";
            case EMPLOYEE -> "user_name"; // Ingestion uses user_name
            case DEPARTMENT -> "department"; // Ingestion uses department
            case TAG -> "tags";
            case KEYWORD -> "keyword";
            default -> null;
        };
    }
}
