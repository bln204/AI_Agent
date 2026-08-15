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
            Filter.Expression current;

            if (entity.getType() == EntityType.EMPLOYEE) {
                // A candidate can verify as EMPLOYEE either because it matches a
                // real system username, or — MySQL's default collation is often
                // accent/case-insensitive — because it coincidentally collides
                // with one. But the same text is just as likely to be a person's
                // name mentioned INSIDE an ingested row (e.g. an XLSX employee
                // list), which lives in a different metadata field (row_values,
                // set by XlsxChunker) than "who uploaded this document"
                // (user_name). Match both instead of assuming user_name is the
                // intended meaning — the security filter is still AND-ed on top,
                // and HybridRetrievalService already falls back to pure semantic
                // if this ends up matching nothing, so widening this can only
                // find more of what the user meant, never leak anything.
                String normalized = com.aiagent.util.NormalizationUtils.normalizeForMatching(entity.getValue());
                current = new Filter.Expression(Filter.ExpressionType.OR,
                        b.eq("user_name", entity.getValue()).build(),
                        b.eq("row_values", normalized).build());
            } else {
                String metadataKey = mapToMetadataKey(entity.getType());
                if (metadataKey == null) continue;

                // ROW_VALUE metadata (XlsxChunker) is stored via
                // NormalizationUtils.normalizeForMatching (lowercase + diacritics
                // stripped) for case/diacritics-insensitive lookup — normalize
                // the query-side value the same way so e.g. "Nguyễn Văn 11" still
                // matches a cell stored as "Nguyen Van 11".
                String filterValue = (entity.getType() == EntityType.ROW_VALUE)
                        ? com.aiagent.util.NormalizationUtils.normalizeForMatching(entity.getValue())
                        : entity.getValue();

                current = b.eq(metadataKey, filterValue).build();
            }

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
            case ROW_VALUE -> "row_values"; // XlsxChunker: literal cell values of one ingested row
            default -> null;
        };
    }
}
