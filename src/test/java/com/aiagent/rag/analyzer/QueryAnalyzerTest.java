package com.aiagent.rag.analyzer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers QueryAnalyzer.extractCandidates, including a regression fix: the
 * "Title Case" regex previously used [A-Z\p{L}], which in Java regex is
 * equivalent to \p{L} alone (Unicode "Letter" already covers upper AND lower
 * case) — so it was accidentally matching whole lowercase sentence
 * fragments as "Title Case phrases" instead of actual proper nouns. Fixed to
 * \p{Lu} (uppercase-only). Also covers the added trailing-numeric-suffix
 * support (e.g. "Nguyễn Văn 3"), needed so a name distinguished only by a
 * number isn't truncated before the part that makes it unique.
 */
class QueryAnalyzerTest {

    private final QueryAnalyzer analyzer = new QueryAnalyzer();

    @Test
    void lowercaseSentenceFragment_isNotExtractedAsTitledPhrase() {
        Set<String> candidates = analyzer.extractCandidates("đang làm ở phòng ban nào");

        assertFalse(candidates.contains("đang làm ở phòng ban nào"),
                "an all-lowercase fragment must not be treated as a Title Case phrase");
    }

    @Test
    void nameWithTrailingNumber_isExtractedAsOneCandidate() {
        Set<String> candidates = analyzer.extractCandidates("Nguyễn Văn 3 đang làm ở phòng ban nào?");

        assertTrue(candidates.contains("Nguyễn Văn 3"),
                "candidates were: " + candidates);
        assertFalse(candidates.contains("đang làm ở phòng ban nào"));
    }

    @Test
    void multiWordTitleCasePhrase_stillDetected() {
        Set<String> candidates = analyzer.extractCandidates("Cho tôi biết thông tin về Tech World");

        assertTrue(candidates.contains("Tech World"), "candidates were: " + candidates);
    }

    @Test
    void singleWordTitleCasePhrase_stillDetected() {
        Set<String> candidates = analyzer.extractCandidates("Vinamilk hoạt động ra sao?");

        assertTrue(candidates.contains("Vinamilk"), "candidates were: " + candidates);
    }

    @Test
    void alphanumericCode_stillDetectedByCodePattern() {
        Set<String> candidates = analyzer.extractCandidates("Nhân viên NV0003 là ai?");

        assertTrue(candidates.contains("NV0003"), "candidates were: " + candidates);
    }

    @Test
    void trailingNumber_notAppendedWhenNotImmediatelyAfterName() {
        Set<String> candidates = analyzer.extractCandidates("Nguyễn Văn A có 5 dự án đang xử lý");

        assertTrue(candidates.contains("Nguyễn Văn A"), "candidates were: " + candidates);
        assertFalse(candidates.contains("Nguyễn Văn A 5"),
                "a number elsewhere in the sentence must not be merged into the name");
    }
}
