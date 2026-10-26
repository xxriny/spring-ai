package dev.springrag.controller;

import dev.springrag.dto.SearchResultDto;
import dev.springrag.dto.ErrorDto;
import dev.springrag.service.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증 범위에 맞는 하이브리드 검색 결과를 제공한다. */
@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "권한 범위별 하이브리드 검색")
@SecurityRequirement(name = "basicAuth")
public class SearchController {
    private final RetrievalService retrieval;

    /**
     * 검색 API를 구성한다.
     *
     * @param retrieval 하이브리드 검색 서비스
     */
    public SearchController(RetrievalService retrieval) { this.retrieval = retrieval; }

    /**
     * 인증 사용자의 접근 범위에서 벡터·BM25 검색과 재정렬 결과를 반환한다.
     *
     * @param auth 현재 인증 정보
     * @param query 검색할 질문
     * @param scope 공용 또는 개인 문서 범위
     * @return 후보 청크와 최종 passage
     * @throws IllegalArgumentException 질문, 범위 또는 사용자 ID가 올바르지 않은 경우
     * @throws dev.springrag.service.Reranker.RerankerUnavailableException 리랭커를 사용할 수 없는 경우
    */
    @Operation(summary = "문서 검색")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "검색 후보와 최종 passage 반환"),
        @ApiResponse(responseCode = "400", description = "질문 또는 범위 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요"),
        @ApiResponse(responseCode = "503", description = "리랭커 사용 불가", content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @GetMapping public SearchResultDto search(Authentication auth,
            @Parameter(description = "검색할 질문", example = "Chat Memory는 어떻게 저장되나요?") @RequestParam String query,
            @Parameter(description = "SHARED는 공용 문서, PRIVATE는 본인 문서", example = "SHARED")
            @RequestParam(defaultValue = "SHARED") String scope) {
        return retrieval.search(query, scope, auth.getName());
    }
}
