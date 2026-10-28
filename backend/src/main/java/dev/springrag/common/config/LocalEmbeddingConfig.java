package dev.springrag.common.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
public class LocalEmbeddingConfig {
    private static final String MODEL = "qwen3-embedding:8b";
    public static final String EMBEDDING_ID = MODEL + "/1536";

    @Bean
    public EmbeddingModel embeddingModel(OllamaApi api, @Value("${spring.ai.ollama.embedding.model}") String model) {
        if (!MODEL.equals(model)) throw new IllegalArgumentException("임베딩 모델 변경 시 기존 벡터 재색인과 모델 식별자 변경이 필요합니다");
        return OllamaEmbeddingModel.builder().ollamaApi(api).options(OllamaEmbeddingOptions.builder()
            .model(model).dimensions(1536).truncate(false).build()).build();
    }
}
