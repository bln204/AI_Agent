package com.aiagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class AiEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() throws Exception {
        // --- 1. Force ONNX Engine to prevent PyTorch initialization issues ---
        System.setProperty("DJL_DEFAULT_ENGINE", "OnnxRuntime");
        
        // --- 2. Synchronized Eager Initialization ---
        log.info("⏳ Initializing Local Transformers Embedding Model (ONNX)...");
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        
        try {
            // This loads the model resources and initializes the engine
            embeddingModel.afterPropertiesSet();
            
            // --- 3. Warm-up Call ---
            // Force native libraries to load and GPU/CPU delegate to bind early
            log.info("🔥 Warming up embedding model with dummy request...");
            embeddingModel.embed("warmup");
            
            log.info("🚀 Local Transformers Embedding Model has been initialized and warmed up successfully!");
        } catch (Exception e) {
            log.error("❌ Failed to initialize Transformers Embedding Model: {}", e.getMessage(), e);
            throw e;
        }
        
        return embeddingModel;
    }
}