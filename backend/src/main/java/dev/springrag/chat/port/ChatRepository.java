package dev.springrag.chat.port;

import dev.springrag.chat.dto.ConversationDto;
import dev.springrag.chat.dto.MessageDto;
import dev.springrag.chat.dto.SourceDto;
import java.util.List;
import java.util.UUID;

/** 대화 소유권과 완료된 메시지의 영속화 계약이다. */
public interface ChatRepository {
    /**
     * 인증 계정의 대화를 조회한다.
     * @param owner 인증 계정
     * @return 계정이 소유한 대화 목록
     */
    List<ConversationDto> list(String owner);

    /**
     * 문서 범위를 고정한 새 대화를 만든다.
     * @param owner 인증 계정
     * @param scope 문서 범위
     * @return 새 대화
     */
    ConversationDto create(String owner, String scope);

    /**
     * 대화에 저장된 메시지를 조회한다.
     * @param id 대화 ID
     * @return 저장된 메시지 목록
     */
    List<MessageDto> messages(UUID id);

    /**
     * 후속 질문을 해소할 최근 메시지를 조회한다.
     * @param id 대화 ID
     * @return 후속 질문을 위한 최근 대화 내용
     */
    List<String> history(UUID id);

    /**
     * 소유권을 확인하고 대화의 검색 범위를 반환한다.
     * @param id 대화 ID
     * @param owner 인증 계정
     * @return 소유한 대화의 문서 범위
     * @throws IllegalArgumentException 해당 계정의 대화가 아닌 경우
     */
    String requireOwned(UUID id, String owner);

    /**
     * 생성이 끝난 답변과 출처를 저장한다.
     * @param id 대화 ID
     * @param question 사용자 질문
     * @param answer 완료된 답변
     * @param sources 답변 출처
     */
    void saveAnswer(UUID id, String question, String answer, List<SourceDto> sources);
}
