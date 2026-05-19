package com.aiagent.util;

import com.aiagent.model.User;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class CacheKeyUtils {

    private CacheKeyUtils() {
        
    }

    public static String generateRetrievalKey(
            String query,
            User user,
            Set<String> activeDocNames
    ) {
        String normalizedQuery = NormalizationUtils.normalize(query);
        String userId = (user != null) ? String.valueOf(user.getId()) : "guest";

        String sortedDocs = "";
        if (activeDocNames != null && !activeDocNames.isEmpty()) {
            sortedDocs = activeDocNames.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(NormalizationUtils::normalize)
                    .collect(Collectors.toCollection(TreeSet::new))
                    .stream()
                    .collect(Collectors.joining(","));
        }

        if (normalizedQuery.length() > 100) {
            normalizedQuery = normalizedQuery.substring(0, 100);
        }

        return String.format(
                "v1|q:%s|u:%s|docs:[%s]",
                normalizedQuery,
                userId,
                sortedDocs
        );
    }
}