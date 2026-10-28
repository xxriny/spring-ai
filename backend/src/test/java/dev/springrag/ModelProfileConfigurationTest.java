package dev.springrag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelProfileConfigurationTest {
    @Test @DisplayName("기본 로컬 프로필은 Ollama만 선택한다")
    void localProfileSelectsOllama() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> {
                var environment = context.getEnvironment();
                assertEquals("ollama", environment.getProperty("spring.ai.model.chat"));
                assertEquals("ollama", environment.getProperty("spring.ai.model.embedding"));
                assertEquals("none", environment.getProperty("spring.ai.model.embedding.text"));
                assertEquals("qwen3-embedding:8b/1536", environment.getProperty("app.embedding-model-id"));
                assertEquals("org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration",
                    environment.getProperty("spring.autoconfigure.exclude"));
            });
    }

    @Test @DisplayName("GCP 프로필은 Vertex 모델과 1536차원 임베딩을 선택한다")
    void gcpProfileSelectsVertexModels() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=gcp", "GOOGLE_CLOUD_PROJECT=spring-rag-demo", "DATABASE_PASSWORD=test-only")
            .run(context -> {
                var environment = context.getEnvironment();
                assertEquals("google-genai", environment.getProperty("spring.ai.model.chat"));
                assertEquals("none", environment.getProperty("spring.ai.model.embedding"));
                assertEquals("google-genai", environment.getProperty("spring.ai.model.embedding.text"));
                assertEquals("gemini-3.5-flash-lite", environment.getProperty("spring.ai.google.genai.chat.model"));
                assertEquals("gemini-embedding-001", environment.getProperty("spring.ai.google.genai.embedding.text.model"));
                assertEquals("1536", environment.getProperty("spring.ai.google.genai.embedding.text.dimensions"));
                assertEquals("1", environment.getProperty("spring.ai.vectorstore.pgvector.max-document-batch-size"));
                assertEquals("global", environment.getProperty("spring.ai.google.genai.location"));
                assertEquals("global", environment.getProperty("spring.ai.google.genai.embedding.location"));
                assertEquals("gemini-embedding-001/1536", environment.getProperty("app.embedding-model-id"));
                assertNull(environment.getProperty("spring.ai.google.genai.api-key"));
                assertNull(environment.getProperty("spring.ai.google.genai.credentials-uri"));
            });
    }
}
