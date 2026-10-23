package dev.springrag;

import dev.springrag.config.LocalEmbeddingConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.test.util.ReflectionTestUtils;

class LocalEmbeddingConfigTest {
    @Test void qwenEightBUsesIndexableEmbeddingDimension() {
        var model = new LocalEmbeddingConfig().embeddingModel(OllamaApi.builder().build(), "qwen3-embedding:8b");
        var options = (OllamaEmbeddingOptions) ReflectionTestUtils.getField(model, "options");
        assertEquals("qwen3-embedding:8b", options.getModel());
        assertEquals(1536, options.getDimensions());
    }

    @Test void rejectsModelThatWouldMislabelVectors() {
        assertThrows(IllegalArgumentException.class, () ->
            new LocalEmbeddingConfig().embeddingModel(OllamaApi.builder().build(), "other-model"));
    }
}
