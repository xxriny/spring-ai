package dev.springrag;

import dev.springrag.dao.ChatDao;
import dev.springrag.dto.SearchHitDto;
import dev.springrag.dto.SearchResultDto;
import dev.springrag.dto.SourceDto;
import dev.springrag.service.ChatService;
import dev.springrag.service.RetrievalService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatStreamingTest {
    @Test
    @DisplayName("답변 조각을 순서대로 전송하고 완료된 답변만 한 번 저장한다")
    void streamsDeltasAndPersistsOnlyCompletedAnswer() {
        UUID id = UUID.randomUUID();
        RecordingDao repository = new RecordingDao();
        var events = service(repository, Flux.just(response("첫"), response("둘")))
            .stream(id, "alice", "question").collectList().block();

        assertEquals(List.of("sources", "delta", "delta", "done"), events.stream().map(e -> e.type()).toList());
        assertEquals(List.of("첫", "둘"), events.stream().filter(e -> "delta".equals(e.type())).map(e -> e.text()).toList());
        assertEquals("Guide", events.get(0).sources().get(0).title());
        assertEquals("첫둘", repository.savedAnswer);
        assertEquals(1, repository.saveCount);
    }

    @Test
    @DisplayName("생성 도중 실패하면 미완료 답변을 저장하지 않는다")
    void doesNotPersistFailedStream() {
        UUID id = UUID.randomUUID();
        RecordingDao repository = new RecordingDao();
        Flux<ChatResponse> failed = Flux.concat(Flux.just(response("일부")), Flux.error(new IllegalStateException("model failed")));

        assertThrows(IllegalStateException.class, () -> service(repository, failed)
            .stream(id, "alice", "question").collectList().block());
        assertEquals(0, repository.saveCount);
    }

    @Test
    @DisplayName("클라이언트가 스트림을 중단하면 미완료 답변을 저장하지 않는다")
    void doesNotPersistCancelledStream() {
        RecordingDao repository = new RecordingDao();
        Flux<ChatResponse> unfinished = Flux.concat(Flux.just(response("일부")), Flux.never());

        var events = service(repository, unfinished).stream(UUID.randomUUID(), "alice", "question")
            .take(2).collectList().block();

        assertEquals(List.of("sources", "delta"), events.stream().map(e -> e.type()).toList());
        assertEquals(0, repository.saveCount);
    }

    private static ChatService service(RecordingDao repository, Flux<ChatResponse> output) {
        var hit = new SearchHitDto("chunk", "doc", "Guide", "url", "", "Guide", "final evidence", null, 1, null, 1.0);
        var retrieval = new RetrievalService(null, null, null) {
            @Override public SearchResultDto search(String query, String scope, String owner) {
                return new SearchResultDto(List.of(), List.of(), List.of(), List.of(), List.of(hit));
            }
        };
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("동기 생성 호출"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return output; }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = (ObjectProvider<ChatModel>) Proxy.newProxyInstance(
            ObjectProvider.class.getClassLoader(), new Class<?>[] {ObjectProvider.class},
            (proxy, method, args) -> method.getName().equals("getIfAvailable") ? model : null);
        ChatMemoryRepository memory = new ChatMemoryRepository() {
            @Override public List<String> findConversationIds() { return List.of(); }
            @Override public List<org.springframework.ai.chat.messages.Message> findByConversationId(String id) { return List.of(); }
            @Override public void saveAll(String id, List<org.springframework.ai.chat.messages.Message> messages) {}
            @Override public void deleteByConversationId(String id) {}
        };
        return new ChatService(repository, retrieval, provider, memory);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static class RecordingDao extends ChatDao {
        private String savedAnswer;
        private int saveCount;

        RecordingDao() { super(new JdbcTemplate()); }
        @Override public String requireOwned(UUID id, String owner) { return "SHARED"; }
        @Override public List<String> history(UUID id) { return List.of(); }
        @Override public void saveAnswer(UUID id, String question, String answer, List<SourceDto> sources) {
            savedAnswer = answer;
            saveCount++;
        }
    }
}
