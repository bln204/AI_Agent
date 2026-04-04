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
        // Cấu hình Local HuggingFace Embedding Model
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        
        // Mặc định nó sẽ tải model "sentence-transformers/all-MiniLM-L6-v2" (384 dimensions)
        // Cache của model này sẽ nằm trong thư mục cấu hình ở application.properties (ví dụ: C:/temp/spring-ai-cache)
        // Bạn có thể xem thư mục này để biết tên và version của HF model đang dùng.
        
        // Bạn có thể chỉ định explicit model name nếu muốn:
        // embeddingModel.setModelResource("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2");
        
        // Bắt buộc gọi afterPropertiesSet() để tải và init model ONNX vào Memory
        embeddingModel.afterPropertiesSet();
        
        log.info("🚀 Local HuggingFace Embedding Model has been initialized successfully!");
        
        return embeddingModel;
    }
}