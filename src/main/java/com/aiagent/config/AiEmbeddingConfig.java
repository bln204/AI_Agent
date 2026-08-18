package com.aiagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class AiEmbeddingConfig {

    // Previously never wired into the bean below, so TransformersEmbeddingModel
    // fell back to its own default cache dir (java.io.tmpdir +
    // "/spring-ai-onnx-generative", i.e. /tmp in the container) instead of this
    // path — meaning the persistent volume mounted here in Docker/Railway was
    // silently unused and the ~86MB ONNX model was re-downloaded from GitHub on
    // every container restart. A transient network hiccup mid-download during
    // any one of those restarts corrupts the file and crash-loops the app
    // (ai.onnxruntime.OrtException: ORT_INVALID_PROTOBUF), which is what was
    // happening to the local docker-compose deployment.
    @Value("${spring.ai.embedding.transformer.cache.directory}")
    private String cacheDirectory;

    @Bean
    public EmbeddingModel embeddingModel() throws Exception {
        System.setProperty("DJL_DEFAULT_ENGINE", "OnnxRuntime");

        log.info("⏳ Initializing Local Transformers Embedding Model (ONNX)...");
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        embeddingModel.setResourceCacheDirectory(cacheDirectory);

        try {
            embeddingModel.afterPropertiesSet();
            
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