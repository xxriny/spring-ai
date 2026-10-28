package dev.springrag;

import dev.springrag.chat.dto.AnswerDto;
import dev.springrag.chat.dto.ConversationDto;
import dev.springrag.chat.port.ChatRepository;
import dev.springrag.chat.port.VisitorRepository;
import dev.springrag.chat.service.ChatService;
import dev.springrag.chat.service.VisitorService;
import dev.springrag.chat.service.VisitorUnauthorizedException;
import dev.springrag.retrieval.dto.SearchResultDto;
import dev.springrag.retrieval.service.RetrievalService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisitorServiceTest {
    @Test @DisplayName("새 방문자마다 다른 토큰을 발급하고 DB에는 해시만 저장한다")
    void issuesDistinctTokensAndStoresOnlyHashes() {
        var repository = mock(VisitorRepository.class);
        var service = new VisitorService(repository, mock(ChatRepository.class), mock(ChatService.class), mock(RetrievalService.class));

        var first = service.issue();
        var second = service.issue();

        assertNotEquals(first.token(), second.token());
        assertTrue(first.expiresAt().isAfter(Instant.now()));
        verify(repository, never()).create(any(), eq(first.token()), any());
        verify(repository, never()).create(any(), eq(second.token()), any());
        verify(repository, org.mockito.Mockito.times(2)).create(any(), any(), any());
    }

    @Test @DisplayName("방문자 질문은 500자를 검사하고 공유 검색 전에 한도를 차감한다")
    void searchChargesBeforeSharedRetrieval() {
        UUID visitor = UUID.randomUUID();
        var sessions = mock(VisitorRepository.class);
        var chats = mock(ChatRepository.class);
        var retrieval = mock(RetrievalService.class);
        when(sessions.requireActive(any())).thenReturn(visitor);
        when(retrieval.search(eq("질문"), eq("SHARED"), any())).thenReturn(new SearchResultDto(List.of(), List.of()));
        var service = new VisitorService(sessions, chats, mock(ChatService.class), retrieval);
        String header = "Bearer " + "a".repeat(43);

        assertThrows(IllegalArgumentException.class, () -> service.search(header, "x".repeat(501)));
        verify(sessions, never()).charge(any());
        service.search(header, "질문");

        var ordered = inOrder(sessions, retrieval);
        ordered.verify(sessions).charge(visitor);
        ordered.verify(retrieval).search("질문", "SHARED", "visitor:" + visitor);
    }

    @Test @DisplayName("방문자 채팅은 소유권을 먼저 확인하고 한도 차감 뒤 모델을 호출한다")
    void chatChecksOwnershipBeforeChargingAndModel() {
        UUID visitor = UUID.randomUUID(), conversation = UUID.randomUUID();
        var sessions = mock(VisitorRepository.class);
        var repository = mock(ChatRepository.class);
        var chats = mock(ChatService.class);
        when(sessions.requireActive(any())).thenReturn(visitor);
        when(repository.requireOwned(conversation, "visitor:" + visitor)).thenReturn("SHARED");
        when(chats.ask(conversation, "visitor:" + visitor, "질문")).thenReturn(new AnswerDto(conversation, "답", List.of()));
        var service = new VisitorService(sessions, repository, chats, mock(RetrievalService.class));
        String header = "Bearer " + "a".repeat(43);

        assertEquals("답", service.ask(header, conversation, "질문").answer());
        var ordered = inOrder(repository, sessions, chats);
        ordered.verify(repository).requireOwned(conversation, "visitor:" + visitor);
        ordered.verify(sessions).charge(visitor);
        ordered.verify(chats).ask(conversation, "visitor:" + visitor, "질문");

        when(repository.requireOwned(conversation, "visitor:" + visitor)).thenReturn("PRIVATE");
        assertThrows(VisitorUnauthorizedException.class, () -> service.ask(header, conversation, "질문"));
    }

    @Test @DisplayName("잘못된 방문자 토큰은 대화에 접근하지 못한다")
    void rejectsMissingOrMalformedBearerToken() {
        var service = new VisitorService(mock(VisitorRepository.class), mock(ChatRepository.class),
            mock(ChatService.class), mock(RetrievalService.class));
        assertThrows(VisitorUnauthorizedException.class, () -> service.list(null));
        assertThrows(VisitorUnauthorizedException.class, () -> service.list("Basic secret"));
    }
}
