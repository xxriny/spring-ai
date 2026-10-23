package dev.springrag;

import dev.springrag.dao.ChatDao;
import dev.springrag.service.ChatService;
import dev.springrag.service.RetrievalService;
import dev.springrag.dto.SearchHitDto;
import dev.springrag.dto.SearchResultDto;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class ChatServiceTest {
    @Test void answerUsesFinalPassagesInsteadOfUncompressedRerankedChunks() {
        UUID id = UUID.randomUUID();
        var repository = new ChatDao(new JdbcTemplate()) {
            @Override public String requireOwned(UUID requested, String owner) { return "SHARED"; }
            @Override public List<String> history(UUID requested) { return List.of(); }
            @Override public void saveAnswer(UUID requested, String question, String answer,
                                             List<dev.springrag.dto.SourceDto> sources) {}
        };
        var wrong = new SearchHitDto("wrong", "doc", "Wrong", "upload", "", "Guide", "discarded text", null, 1, null, 1.0);
        var right = new SearchHitDto("right", "doc", "Right", "upload", "", "Guide", "final evidence", null, 1, null, 1.0);
        var retrieval = new RetrievalService(null, null, null) {
            @Override public SearchResultDto search(String query, String scope, String owner) {
                return new SearchResultDto(List.of(), List.of(), List.of(), List.of(wrong), List.of(right));
            }
        };
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                assertTrue(prompt.getInstructions().toString().contains("final evidence"));
                assertFalse(prompt.getInstructions().toString().contains("discarded text"));
                return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
            }
        };
        ObjectProvider<ChatModel> models = (ObjectProvider<ChatModel>) Proxy.newProxyInstance(
            ObjectProvider.class.getClassLoader(), new Class<?>[] {ObjectProvider.class},
            (proxy, method, args) -> method.getName().equals("getIfAvailable") ? model : null);
        ChatMemoryRepository memory = new ChatMemoryRepository() {
            @Override public List<String> findConversationIds() { return List.of(); }
            @Override public List<org.springframework.ai.chat.messages.Message> findByConversationId(String conversationId) { return List.of(); }
            @Override public void saveAll(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {}
            @Override public void deleteByConversationId(String conversationId) {}
        };
        var answer = new ChatService(repository, retrieval, models, memory).ask(id, "alice", "question");
        assertEquals("Right", answer.sources().get(0).title());
    }
    @Test void checksConversationOwnerBeforeModelAndPreservesMissingModelResponse() {
        UUID id = UUID.randomUUID();
        var repository = new ChatDao(new JdbcTemplate()) {
            @Override public String requireOwned(UUID requested, String owner) {
                if (!"alice".equals(owner)) throw new IllegalArgumentException("대화를 찾을 수 없습니다");
                return "PRIVATE";
            }
        };
        ObjectProvider<ChatModel> models = (ObjectProvider<ChatModel>) Proxy.newProxyInstance(
            ObjectProvider.class.getClassLoader(), new Class<?>[] {ObjectProvider.class},
            (proxy, method, args) -> method.getName().equals("getIfAvailable") ? null : null);
        ChatMemoryRepository memory = (ChatMemoryRepository) Proxy.newProxyInstance(
            ChatMemoryRepository.class.getClassLoader(), new Class<?>[] {ChatMemoryRepository.class},
            (proxy, method, args) -> null);
        var service = new ChatService(repository, null, models, memory);
        assertThrows(IllegalArgumentException.class, () -> service.ask(id, "bob", "question"));
        assertThrows(ChatService.ChatUnavailableException.class, () -> service.ask(id, "alice", "question"));
    }
}
