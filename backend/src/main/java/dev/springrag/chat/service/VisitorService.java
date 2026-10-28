package dev.springrag.chat.service;

import dev.springrag.chat.dto.AnswerDto;
import dev.springrag.chat.dto.ChatStreamEvent;
import dev.springrag.chat.dto.ConversationDto;
import dev.springrag.chat.dto.MessageDto;
import dev.springrag.chat.dto.VisitorSessionDto;
import dev.springrag.chat.port.ChatRepository;
import dev.springrag.chat.port.VisitorRepository;
import dev.springrag.retrieval.dto.SearchResultDto;
import dev.springrag.retrieval.service.RetrievalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** 공개 방문자의 만료 세션과 공용 문서 전용 대화·검색을 관리한다. */
@Service
@Profile("gcp")
public class VisitorService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final VisitorRepository visitors;
    private final ChatRepository repository;
    private final ChatService chats;
    private final RetrievalService retrieval;

    /**
     * 방문자 API를 기존 대화·검색 서비스에 연결한다.
     * @param visitors 방문자 세션·일일 한도 저장소
     * @param repository 대화 소유권 저장소
     * @param chats 대화 서비스
     * @param retrieval 공용 문서 검색 서비스
     */
    public VisitorService(VisitorRepository visitors, ChatRepository repository, ChatService chats, RetrievalService retrieval) {
        this.visitors = visitors;
        this.repository = repository;
        this.chats = chats;
        this.retrieval = retrieval;
    }

    /**
     * 새 브라우저용 24시간 세션을 발급한다.
     * @return 원문 토큰과 만료 시각
     */
    public VisitorSessionDto issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plusSeconds(24 * 60 * 60);
        visitors.create(UUID.randomUUID(), hash(token), expiresAt);
        return new VisitorSessionDto(token, expiresAt);
    }

    /**
     * 현재 방문자의 대화 목록을 조회한다.
     * @param authorization Bearer 토큰 헤더
     * @return 현재 방문자 대화 목록
     * @throws VisitorUnauthorizedException 토큰이 없거나 만료된 경우
     */
    public List<ConversationDto> list(String authorization) {
        return chats.list(owner(authenticate(authorization)));
    }

    /**
     * 현재 방문자의 공용 문서 대화를 만든다.
     * @param authorization Bearer 토큰 헤더
     * @return 공용 문서 대화
     * @throws VisitorUnauthorizedException 토큰이 없거나 만료된 경우
     */
    public ConversationDto create(String authorization) {
        return chats.create(owner(authenticate(authorization)), "SHARED");
    }

    /**
     * 현재 방문자가 소유한 대화 메시지를 조회한다.
     * @param authorization Bearer 토큰 헤더
     * @param id 대화 ID
     * @return 저장된 메시지
     * @throws VisitorUnauthorizedException 토큰이 없거나 만료된 경우
     * @throws IllegalArgumentException 대화 소유권이 없는 경우
     */
    public List<MessageDto> messages(String authorization, UUID id) {
        return chats.messages(id, owner(authenticate(authorization)));
    }

    /**
     * 방문자 한도를 먼저 차감하고 공용 문서 답변을 생성한다.
     * @param authorization Bearer 토큰 헤더
     * @param id 대화 ID
     * @param question 1~500자 질문
     * @return 답변과 출처
     * @throws VisitorUnauthorizedException 토큰이 없거나 공용 대화가 아닌 경우
     * @throws VisitorRateLimitException 일일 한도를 넘은 경우
     * @throws IllegalArgumentException 질문 또는 대화 소유권이 유효하지 않은 경우
     */
    public AnswerDto ask(String authorization, UUID id, String question) {
        UUID visitor = authenticate(authorization);
        String text = question(question);
        String owner = owner(visitor);
        requireShared(id, owner);
        visitors.charge(visitor);
        return chats.ask(id, owner, text);
    }

    /**
     * 방문자 한도를 먼저 차감하고 공용 문서 답변 스트림을 연다.
     * @param authorization Bearer 토큰 헤더
     * @param id 대화 ID
     * @param question 1~500자 질문
     * @return 답변 NDJSON 이벤트 스트림
     * @throws VisitorUnauthorizedException 토큰이 없거나 공용 대화가 아닌 경우
     * @throws VisitorRateLimitException 일일 한도를 넘은 경우
     * @throws IllegalArgumentException 질문 또는 대화 소유권이 유효하지 않은 경우
     */
    public Flux<ChatStreamEvent> stream(String authorization, UUID id, String question) {
        UUID visitor = authenticate(authorization);
        String text = question(question);
        String owner = owner(visitor);
        requireShared(id, owner);
        visitors.charge(visitor);
        return chats.stream(id, owner, text);
    }

    /**
     * 방문자 한도를 먼저 차감하고 공용 문서만 검색한다.
     * @param authorization Bearer 토큰 헤더
     * @param query 1~500자 검색 질문
     * @return 공용 문서 검색 결과
     * @throws VisitorUnauthorizedException 토큰이 없거나 만료된 경우
     * @throws VisitorRateLimitException 일일 한도를 넘은 경우
     * @throws IllegalArgumentException 질문 형식이 유효하지 않은 경우
     */
    public SearchResultDto search(String authorization, String query) {
        UUID visitor = authenticate(authorization);
        String text = question(query);
        visitors.charge(visitor);
        return retrieval.search(text, "SHARED", owner(visitor));
    }

    private UUID authenticate(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}"))
            throw new VisitorUnauthorizedException();
        return visitors.requireActive(hash(authorization.substring(7)));
    }

    private void requireShared(UUID id, String owner) {
        if (!"SHARED".equals(repository.requireOwned(id, owner))) throw new VisitorUnauthorizedException();
    }

    private static String question(String value) {
        if (value == null || value.isBlank() || value.length() > 500)
            throw new IllegalArgumentException("방문자 질문은 1~500자여야 합니다");
        return value.trim();
    }

    private static String owner(UUID visitor) { return "visitor:" + visitor; }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
