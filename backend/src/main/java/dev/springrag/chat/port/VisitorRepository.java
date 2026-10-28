package dev.springrag.chat.port;

import java.time.Instant;
import java.util.UUID;

/** 방문자 세션과 Vertex 호출 전 일일 사용량의 영속화 계약이다. */
public interface VisitorRepository {
    /**
     * 원문 대신 해시로 방문자 토큰을 저장한다.
     * @param id 방문자 세션 ID
     * @param tokenHash 토큰 SHA-256 해시
     * @param expiresAt 만료 시각
     */
    void create(UUID id, String tokenHash, Instant expiresAt);

    /**
     * 토큰 해시가 가리키는 활성 세션을 확인한다.
     * @param tokenHash 토큰 SHA-256 해시
     * @return 활성 방문자 세션 ID
     * @throws dev.springrag.chat.service.VisitorUnauthorizedException 토큰이 없거나 만료된 경우
     */
    UUID requireActive(String tokenHash);

    /**
     * 활성 세션을 확인하고 방문자·전체 한도를 한 트랜잭션에서 차감한다.
     * @param visitorId 방문자 세션 ID
     * @throws dev.springrag.chat.service.VisitorUnauthorizedException 세션이 만료된 경우
     * @throws dev.springrag.chat.service.VisitorRateLimitException 어느 한도라도 소진된 경우
     */
    void charge(UUID visitorId);
}
