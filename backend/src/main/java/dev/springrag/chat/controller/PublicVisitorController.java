package dev.springrag.chat.controller;

import dev.springrag.chat.dto.AnswerDto;
import dev.springrag.chat.dto.ChatStreamEvent;
import dev.springrag.chat.dto.ConversationDto;
import dev.springrag.chat.dto.MessageDto;
import dev.springrag.chat.dto.QuestionRequest;
import dev.springrag.chat.dto.VisitorSearchRequest;
import dev.springrag.chat.dto.VisitorSessionDto;
import dev.springrag.chat.service.VisitorService;
import dev.springrag.common.dto.ErrorDto;
import dev.springrag.retrieval.dto.SearchResultDto;
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
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** GCP 시연 환경에서만 방문자별 공용 문서 검색과 대화를 제공한다. */
@RestController
@Profile("gcp")
@RequestMapping("/api/public")
@Tag(name = "Public visitor", description = "만료되는 방문자 세션과 공용 문서 체험")
public class PublicVisitorController {
    private final VisitorService visitors;

    /**
     * 공개 체험 서비스를 연결한다.
     * @param visitors 방문자 인증·사용 한도 서비스
     */
    public PublicVisitorController(VisitorService visitors) { this.visitors = visitors; }

    /**
     * 새 방문자에게 24시간 Bearer 세션을 발급한다.
     * @return 원문 토큰과 만료 시각을 담은 저장 금지 응답
     */
    @Operation(summary = "방문자 세션 발급", description = "토큰은 발급 시 한 번만 반환하며 서버에는 해시만 보관합니다.")
    @ApiResponse(responseCode = "200", description = "24시간 방문자 세션")
    @PostMapping("/sessions")
    public ResponseEntity<VisitorSessionDto> issue() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visitors.issue());
    }

    /**
     * 방문자의 대화 목록을 조회한다.
     * @param authorization 방문자 Bearer 토큰
     * @return 방문자 소유의 대화 목록
     */
    @Operation(summary = "방문자 대화 목록")
    @SecurityRequirement(name = "visitorBearer")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "본인 대화 목록"),
        @ApiResponse(responseCode = "401", description = "방문자 세션 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class)))})
    @GetMapping("/conversations")
    public List<ConversationDto> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return visitors.list(authorization);
    }

    /**
     * 공용 문서만 사용하는 새 대화를 만든다.
     * @param authorization 방문자 Bearer 토큰
     * @return 새 대화
     */
    @Operation(summary = "공용 문서 대화 생성")
    @SecurityRequirement(name = "visitorBearer")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "새 대화"),
        @ApiResponse(responseCode = "401", description = "방문자 세션 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class)))})
    @PostMapping("/conversations")
    public ConversationDto create(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return visitors.create(authorization);
    }

    /**
     * 현재 방문자 대화의 메시지만 조회한다.
     * @param authorization 방문자 Bearer 토큰
     * @param id 대화 ID
     * @return 저장된 대화 메시지
     * @throws IllegalArgumentException 대화 소유자가 다르거나 대화가 없는 경우
     */
    @Operation(summary = "방문자 대화 메시지 조회")
    @SecurityRequirement(name = "visitorBearer")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "본인 메시지 목록"),
        @ApiResponse(responseCode = "400", description = "대화 소유권 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "방문자 세션 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class)))})
    @GetMapping("/conversations/{id}/messages")
    public List<MessageDto> messages(@RequestHeader(value = "Authorization", required = false) String authorization,
        @Parameter(description = "본인 대화 ID") @PathVariable UUID id) {
        return visitors.messages(authorization, id);
    }

    /**
     * 방문자 한도를 차감한 뒤 공용 문서를 검색한다.
     * @param authorization 방문자 Bearer 토큰
     * @param request 1~500자 검색 질문
     * @return 공용 문서 검색 단계와 최종 passage
     */
    @Operation(summary = "공용 문서 검색", description = "Vertex 임베딩 호출 전에 방문자·전체 일일 한도를 원자적으로 차감합니다.")
    @SecurityRequirement(name = "visitorBearer")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "공용 문서 검색 결과"),
        @ApiResponse(responseCode = "400", description = "질문 형식 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "방문자 세션 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "429", description = "일일 한도 초과", content = @Content(schema = @Schema(implementation = ErrorDto.class)))})
    @PostMapping("/search")
    public SearchResultDto search(@RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody VisitorSearchRequest request) {
        return visitors.search(authorization, request.query());
    }

    /**
     * 방문자 한도를 차감한 뒤 공용 문서에 근거한 답변을 생성한다.
     * @param authorization 방문자 Bearer 토큰
     * @param id 본인 대화 ID
     * @param request 1~500자 질문
     * @return 답변과 출처
     */
    @Operation(summary = "공용 문서 질문")
    @SecurityRequirement(name = "visitorBearer")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "답변과 근거"),
        @ApiResponse(responseCode = "400", description = "질문 또는 대화 소유권 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "방문자 세션 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "429", description = "일일 한도 초과", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "503", description = "모델 또는 리랭커 사용 불가", content = @Content(schema = @Schema(implementation = ErrorDto.class)))})
    @PostMapping("/conversations/{id}/messages")
    public AnswerDto ask(@RequestHeader(value = "Authorization", required = false) String authorization,
        @Parameter(description = "본인 대화 ID") @PathVariable UUID id, @RequestBody QuestionRequest request) {
        return visitors.ask(authorization, id, request.text());
    }

    /**
     * 방문자 한도를 차감한 뒤 공용 문서 답변을 NDJSON으로 전송한다.
     * @param authorization 방문자 Bearer 토큰
     * @param id 본인 대화 ID
     * @param request 1~500자 질문
     * @return sources, delta, done 이벤트 스트림
     */
    @Operation(summary = "공용 문서 질문 스트리밍")
    @SecurityRequirement(name = "visitorBearer")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "답변 NDJSON 이벤트", content = @Content(mediaType = MediaType.APPLICATION_NDJSON_VALUE, schema = @Schema(implementation = ChatStreamEvent.class))),
        @ApiResponse(responseCode = "400", description = "질문 또는 대화 소유권 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "방문자 세션 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "429", description = "일일 한도 초과", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "503", description = "모델 또는 리랭커 사용 불가", content = @Content(schema = @Schema(implementation = ErrorDto.class)))})
    @PostMapping(value = "/conversations/{id}/messages/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<ChatStreamEvent> stream(@RequestHeader(value = "Authorization", required = false) String authorization,
        @Parameter(description = "본인 대화 ID") @PathVariable UUID id, @RequestBody QuestionRequest request) {
        return visitors.stream(authorization, id, request.text());
    }
}
