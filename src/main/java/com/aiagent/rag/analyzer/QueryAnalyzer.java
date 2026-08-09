package com.aiagent.rag.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class QueryAnalyzer {

    // Pattern for alphanumeric codes (e.g., HD001, Test_V10, INV-2025-001)
    private static final Pattern CODE_PATTERN = Pattern.compile("\\b[A-Za-z0-9][A-Za-z0-9_\\-]{2,}\\b");
    
    // Pattern for Title Case phrases (e.g., Tech World, Vinamilk, Nguyễn Văn A)
    private static final Pattern TITLED_PHRASE_PATTERN = Pattern.compile("([A-Z\\p{L}][\\p{L}0-9_\\-]*(\\s+[A-Z\\p{L}][\\p{L}0-9_\\-]*)+)|(\\b[A-Z\\p{L}][\\p{L}]{2,}\\b)");

    public Set<String> extractCandidates(String question) {
        log.info("[QUERY-ANALYZER] Extracting candidates from question of length: {} chars", question.length());
        
        Set<String> candidates = new LinkedHashSet<>();
        
        // Remove common prefixes used in RAG to cleaner extraction
        String cleanText = question.replaceAll("(?i)^(dự án|tài liệu|văn bản|khách hàng|nhân viên|phòng|ban|hợp đồng|hóa đơn|ông|bà|anh|chị)\\b", "").trim();

        // Alphanumeric codes
        Matcher codeMatcher = CODE_PATTERN.matcher(cleanText);
        while (codeMatcher.find()) {
            candidates.add(codeMatcher.group().trim());
        }

        // Title Case phrases
        Matcher titleMatcher = TITLED_PHRASE_PATTERN.matcher(cleanText);
        while (titleMatcher.find()) {
            candidates.add(titleMatcher.group().trim());
        }

        log.info("[CANDIDATE] Detected Candidates: {}", candidates);
        return candidates;
    }
}
