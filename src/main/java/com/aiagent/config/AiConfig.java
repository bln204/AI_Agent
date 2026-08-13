package com.aiagent.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.boot.CommandLineRunner;
import java.util.Map;
import java.util.HashMap;


import org.springframework.retry.annotation.EnableRetry;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@EnableRetry
public class AiConfig {

    @Value("${qdrant.host}")
    private String qdrantHost;

    @Value("${qdrant.port}")
    private int qdrantPort;

    @Value("${qdrant.collection-name}")
    private String collectionName;

    @Bean
    public org.springframework.boot.web.client.RestTemplateCustomizer customRestTemplateCustomizer() {
        return restTemplate -> {
            restTemplate.getMessageConverters().add(0, new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        };
    }

    @Bean
    public CommandLineRunner initializeQdrant() {
        return args -> {
            // Qdrant container may still be finishing startup even though Docker
            // reports it healthy (race between healthcheck poll and readiness) or
            // may hiccup transiently on a slow host, so retry a few times with
            // backoff instead of giving up after a single failed attempt — a
            // silent failure here leaves RAG broken until the app is restarted.
            int maxAttempts = 5;
            long backoffMillis = 2000;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    RestTemplate restTemplate = new RestTemplate();
                    String url = String.format("http://%s:6333/collections/%s", qdrantHost, collectionName);

                    log.info("Verifying Qdrant collection via REST (attempt {}/{}): {}", attempt, maxAttempts, url);

                    try {
                        restTemplate.getForObject(url, String.class);
                        log.info("Qdrant collection '{}' already exists.", collectionName);
                    } catch (Exception e) {
                        log.warn("Collection '{}' not found, creating with 384 dimensions (BGE-Small)...", collectionName);

                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);

                        Map<String, Object> vectors = new HashMap<>();
                        vectors.put("size", 384); // BGE-small-en-v1.5 has 384 dimensions
                        vectors.put("distance", "Cosine");

                        Map<String, Object> body = new HashMap<>();
                        body.put("vectors", vectors);

                        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                        restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
                        log.info("Successfully created Qdrant collection: {}", collectionName);
                    }
                    return; // success, no more retries needed
                } catch (Exception ex) {
                    if (attempt == maxAttempts) {
                        log.error("Critical error during Qdrant initialization after {} attempts: {}", maxAttempts, ex.getMessage());
                    } else {
                        log.warn("Qdrant not reachable yet (attempt {}/{}): {}. Retrying in {}ms...",
                                attempt, maxAttempts, ex.getMessage(), backoffMillis);
                        try {
                            Thread.sleep(backoffMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        backoffMillis *= 2;
                    }
                }
            }
        };
    }
}
