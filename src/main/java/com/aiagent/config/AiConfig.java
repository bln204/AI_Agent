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


import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class AiConfig {

    @Value("${qdrant.host}")
    private String qdrantHost;

    @Value("${qdrant.port}")
    private int qdrantPort;

    @Value("${qdrant.collection-name}")
    private String collectionName;

    /**
     * ChatModel is auto-configured by
     * spring-ai-google-ai-gemini-spring-boot-starter
     * using properties: spring.ai.google.ai.gemini.*
     */

    @Bean
    public org.springframework.boot.web.client.RestTemplateCustomizer customRestTemplateCustomizer() {
        return restTemplate -> {
            restTemplate.getMessageConverters().add(0, new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        };
    }




    @Bean
    public CommandLineRunner initializeQdrant() {
        return args -> {
            try {
                RestTemplate restTemplate = new RestTemplate();
                String url = String.format("http://%s:6333/collections/%s", qdrantHost, collectionName);

                log.info("Verifying Qdrant collection via REST: {}", url);

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
            } catch (Exception ex) {
                log.error("Critical error during Qdrant initialization: {}", ex.getMessage());
            }
        };
    }
}
