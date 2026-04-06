package com.aiagent.config;

import com.aiagent.rag.RagReindexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runner to trigger RAG re-indexing on application startup.
 * Controlled by configuration flag app.rag.reindex-on-startup (default: false).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagReindexRunner implements ApplicationRunner {

    private final RagReindexService ragReindexService;

    @Value("${app.rag.reindex-on-startup:false}")
    private boolean reindexOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (reindexOnStartup) {
            log.info("[RAG-REINDEX-RUNNER] Startup re-indexing is ENABLED. Starting process...");
            try {
                ragReindexService.reindexAllDocuments();
                log.info("[RAG-REINDEX-RUNNER] Startup re-indexing completed successfully.");
            } catch (Exception e) {
                log.error("[RAG-REINDEX-RUNNER] Startup re-indexing FAILED!", e);
            }
        } else {
            log.debug("[RAG-REINDEX-RUNNER] Startup re-indexing is DISABLED. Skipping.");
        }
    }
}
