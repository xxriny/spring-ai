package dev.springrag.crawl.controller;

import dev.springrag.crawl.dto.CrawlRequest;
import dev.springrag.crawl.dto.CrawlResultDto;
import dev.springrag.common.dto.ErrorDto;
import dev.springrag.crawl.service.SiteCrawler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 전용 URL 수집 요청을 처리한다. */
@RestController
@RequestMapping("/api/admin/crawl")
@Tag(name = "Crawl", description = "관리자 URL 수집")
@SecurityRequirement(name = "basicAuth")
public class CrawlController {
    private final SiteCrawler crawler;

    /**
     * URL 수집 API를 구성한다.
     *
     * @param crawler 사이트 수집 서비스
     */
    public CrawlController(SiteCrawler crawler) { this.crawler = crawler; }

    /**
     * 관리자 요청의 시작 URL과 수집 한도에 따라 하위 페이지를 등록한다.
     *
     * @param seed 시작 URL, 카테고리, 최대 페이지 수
     * @return 등록 및 오류 건수
     * @throws IllegalArgumentException URL이 없거나 수집 조건이 잘못된 경우
    */
    @Operation(summary = "하위 페이지 수집", description = "관리자 권한이 필요합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "색인·건너뛰기·오류 결과"),
        @ApiResponse(responseCode = "400", description = "URL 또는 수집 조건 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요"),
        @ApiResponse(responseCode = "403", description = "관리자 권한 필요")
    })
    @PostMapping public CrawlResultDto crawl(@RequestBody CrawlRequest seed) {
        if (seed.url() == null || seed.url().isBlank()) throw new IllegalArgumentException("URL이 필요합니다");
        return crawler.crawl(seed.url(), seed.category(), seed.maxPages() == null ? 100 : seed.maxPages());
    }
}
