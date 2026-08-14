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
    
    // Pattern for Title Case phrases (e.g., Tech World, Vinamilk, Nguyễn Văn A).
    // Uses \p{Lu} (uppercase letter) rather than [A-Z\p{L}] — \p{L} already
    // matches every letter regardless of case, so [A-Z\p{L}] was accidentally
    // equivalent to "any letter" and matched whole lowercase sentence
    // fragments instead of actual Title Case phrases. A trailing standalone
    // number is also allowed (e.g. "Nguyễn Văn 3") so a name distinguished
    // only by a numeric suffix — common in row-based data like an employee
    // list — isn't truncated before the part that makes it unique.
    private static final Pattern TITLED_PHRASE_PATTERN = Pattern.compile(
            "(\\p{Lu}[\\p{L}0-9_\\-]*(\\s+\\p{Lu}[\\p{L}0-9_\\-]*)+(\\s+\\d+)?)|(\\b\\p{Lu}[\\p{L}]{2,}\\b)");

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
