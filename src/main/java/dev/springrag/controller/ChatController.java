package dev.springrag.controller;

import dev.springrag.dto.AnswerDto;
import dev.springrag.dto.ChatStreamEvent;
import dev.springrag.dto.ConversationDto;
import dev.springrag.dto.ConversationRequest;
import dev.springrag.dto.ErrorDto;
import dev.springrag.dto.MessageDto;
import dev.springrag.dto.QuestionRequest;
import dev.springrag.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** 사용자의 대화와 그 안의 질문 요청을 처리한다. */
@RestController
@RequestMapping("/api/conversations")
@Tag(name = "Chat", description = "대화 및 질문")
@SecurityRequirement(name = "basicAuth")
public class ChatController {
    private final ChatService chats;

    /**
     * 대화 API를 구성한다.
     *
     * @param chats 대화 서비스
     */
    public ChatController(ChatService chats) { this.chats = chats; }

    /**
     * 인증 사용자의 대화를 조회한다.
     *
     * @param auth 현재 인증 정보
     * @return 대화 목록
    */
    @Operation(summary = "대화 목록 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "본인 대화 목록"),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping public List<ConversationDto> conversations(Authentication auth) {
        return chats.list(auth.getName());
    }

    /**
     * 공용 또는 개인 문서 범위를 고정한 대화를 만든다.
     *
     * @param auth 현재 인증 정보
     * @param request 대화 범위
     * @return 생성된 대화
     * @throws IllegalArgumentException 범위가 올바르지 않은 경우
    */
    @Operation(summary = "대화 생성")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "생성된 대화"),
        @ApiResponse(responseCode = "400", description = "범위 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @PostMapping public ConversationDto create(Authentication auth, @RequestBody ConversationRequest request) {
        return chats.create(auth.getName(), request.scope());
    }

    /**
     * 본인 대화의 저장된 메시지를 조회한다.
     *
     * @param auth 현재 인증 정보
     * @param id 대화 ID
     * @return 저장된 메시지 목록
     * @throws IllegalArgumentException 본인 대화가 아닌 경우
    */
    @Operation(summary = "대화 메시지 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "저장된 대화 메시지"),
        @ApiResponse(responseCode = "400", description = "대화가 없거나 소유자 불일치", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping("/{id}/messages") public List<MessageDto> messages(Authentication auth,
        @Parameter(description = "본인 대화 ID") @PathVariable UUID id) {
        return chats.messages(id, auth.getName());
    }

    /**
     * 대화 범위에서 근거를 검색하고 답변을 저장한다.
     *
     * @param auth 현재 인증 정보
     * @param id 대화 ID
     * @param request 질문 본문
     * @return 답변과 출처
     * @throws IllegalArgumentException 대화 소유권 또는 질문이 올바르지 않은 경우
     * @throws dev.springrag.service.ChatService.ChatUnavailableException 생성 모델이 설정되지 않은 경우
     * @throws dev.springrag.service.Reranker.RerankerUnavailableException 검색 재정렬 서버를 사용할 수 없는 경우
    */
    @Operation(summary = "질문과 답변", description = "Ollama 생성 모델로 최종 검색 passage에 근거한 답변을 만듭니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "답변과 근거 출처"),
        @ApiResponse(responseCode = "400", description = "대화 소유권 또는 질문 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요"),
        @ApiResponse(responseCode = "503", description = "생성 모델 또는 리랭커 사용 불가", content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PostMapping("/{id}/messages") public AnswerDto ask(Authentication auth,
        @Parameter(description = "본인 대화 ID") @PathVariable UUID id,
                                                  @RequestBody QuestionRequest request) {
        return chats.ask(id, auth.getName(), request.text());
    }

    /**
     * 질문의 근거와 생성 조각을 순서대로 전송하고 완료된 답변을 저장한다.
     *
     * @param auth 현재 인증 정보
     * @param id 본인 대화 ID
     * @param request 질문 본문
     * @return sources, delta, done NDJSON 이벤트 스트림
     * @throws IllegalArgumentException 대화 소유권 또는 질문이 올바르지 않은 경우
     * @throws dev.springrag.service.ChatService.ChatUnavailableException 생성 모델이 없는 경우
     * @throws dev.springrag.service.Reranker.RerankerUnavailableException 검색 재정렬 서버를 사용할 수 없는 경우
     */
    @Operation(summary = "질문과 답변 스트리밍", description = "최종 passage를 검색한 뒤 Ollama 답변을 생성되는 순서대로 전송합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "출처·답변 조각·완료 NDJSON 이벤트", content = @Content(mediaType = MediaType.APPLICATION_NDJSON_VALUE, schema = @Schema(implementation = ChatStreamEvent.class))),
        @ApiResponse(responseCode = "400", description = "대화 소유권 또는 질문 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요"),
        @ApiResponse(responseCode = "503", description = "생성 모델 또는 리랭커 사용 불가", content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<ChatStreamEvent> stream(Authentication auth,
        @Parameter(description = "본인 대화 ID") @PathVariable UUID id,
        @RequestBody QuestionRequest request) {
        return chats.stream(id, auth.getName(), request.text());
    }
}
