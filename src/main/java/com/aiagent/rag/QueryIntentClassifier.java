package com.aiagent.rag;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.HashSet;

@Component
public class QueryIntentClassifier {

    public enum Intent {
        SPECIFIC_LOOKUP,
        ABSTRACT_EXPLANATION,
        MIXED
    }

    public record ClassificationResult(Intent intent, Set<String> titleTokens) {}

    // Pattern for alphanumeric codes like TEST_V5, ABC-123_V2, PROJECT_X_123
    private static final Pattern CODE_PATTERN = Pattern.compile("(?i)\\b([a-z0-9]+(?:[_-][a-z0-9]+)+|[a-z]{1,3}\\d{1,5})\\b");

    private static final Set<String> ABSTRACT_KEYWORDS = Set.of(
        "rui ro", "van de", "kien truc", "bao mat", "so sanh", "tom tat", 
        "tong quan", "quy trinh", "huong dan", "chinh sach", "thong tin chung"
    );

    /**
     * Classify the query intent and extract potential document identifiers.
     */
    public ClassificationResult classify(String query, String normalizedQuery) {
        Set<String> tokens = extractTitleTokens(normalizedQuery);
        
        boolean hasSpecificTokens = !tokens.isEmpty();
        boolean hasAbstractKeywords = containsAbstractKeywords(normalizedQuery);

        Intent intent;
        if (hasSpecificTokens && hasAbstractKeywords) {
            intent = Intent.MIXED;
        } else if (hasSpecificTokens) {
            intent = Intent.SPECIFIC_LOOKUP;
        } else {
            intent = Intent.ABSTRACT_EXPLANATION;
        }

        return new ClassificationResult(intent, tokens);
    }

    private Set<String> extractTitleTokens(String normalizedQuery) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = CODE_PATTERN.matcher(normalizedQuery);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase());
        }
        return tokens;
    }

    private boolean containsAbstractKeywords(String normalizedQuery) {
        return ABSTRACT_KEYWORDS.stream().anyMatch(normalizedQuery::contains);
    }
}
