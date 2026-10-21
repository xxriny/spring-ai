package dev.springrag;

import dev.springrag.dao.ChatDao;
import dev.springrag.service.ChatService;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class ChatServiceTest {
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
