package dev.springrag.controller;

import dev.springrag.dto.DocumentDto;
import dev.springrag.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import dev.springrag.dto.ErrorDto;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 인증 사용자의 문서 조회·등록·삭제 요청을 문서 서비스에 연결한다. */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "Knowledge", description = "공용·개인 문서 관리")
@SecurityRequirement(name = "basicAuth")
public class KnowledgeController {
    private final KnowledgeService knowledge;

    /**
     * 문서 관리 API를 구성한다.
     *
     * @param knowledge 문서 서비스
     */
    public KnowledgeController(KnowledgeService knowledge) { this.knowledge = knowledge; }

    /**
     * 지정한 범위에서 현재 사용자가 볼 수 있는 문서를 조회한다.
     *
     * @param auth 현재 인증 정보
     * @param scope 공용 또는 개인 문서 범위
     * @return 접근 가능한 문서 목록
    */
    @Operation(summary = "문서 목록 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "접근 가능한 문서 목록"),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping public List<DocumentDto> documents(Authentication auth,
        @Parameter(description = "SHARED는 공용 문서, PRIVATE는 본인 문서", example = "SHARED")
        @RequestParam(defaultValue = "SHARED") String scope) {
        return knowledge.list(auth.getName(), scope);
    }

    /**
     * 파일을 등록하고 현재 사용자의 권한에 따른 범위로 색인한다.
     *
     * @param auth 현재 인증 정보
     * @param file 등록할 파일
     * @param category 문서 카테고리
     * @return 등록된 문서 정보
     * @throws IOException 파일을 읽거나 저장할 수 없는 경우
     * @throws IllegalArgumentException 파일 형식이나 카테고리가 올바르지 않은 경우
     * @throws dev.springrag.service.PdfExtractionClient.PdfExtractionUnavailableException PDF 추출 서버를 사용할 수 없는 경우
    */
    @Operation(summary = "문서 파일 등록")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "등록된 문서 정보"),
        @ApiResponse(responseCode = "400", description = "파일 또는 카테고리 오류", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요"),
        @ApiResponse(responseCode = "503", description = "PDF 추출 또는 OCR 사용 불가", content = @Content(schema = @Schema(implementation = ErrorDto.class)))
    })
    @PostMapping public DocumentDto upload(Authentication auth,
        @Parameter(description = "TXT, PDF, DOC, DOCX, HTML 파일") @RequestParam MultipartFile file,
        @Parameter(description = "1~80자 문서 카테고리", example = "Spring AI docs") @RequestParam String category) throws IOException {
        return knowledge.upload(file, category, auth.getName(), admin(auth));
    }

    /**
     * 관리자 공용 문서 또는 사용자 본인 문서를 삭제한다.
     *
     * @param auth 현재 인증 정보
     * @param id 삭제할 문서 ID
     * @throws IllegalArgumentException 문서를 찾을 수 없거나 삭제 권한이 없는 경우
    */
    @Operation(summary = "문서 삭제")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 완료"),
        @ApiResponse(responseCode = "400", description = "문서가 없거나 삭제 권한 없음", content = @Content(schema = @Schema(implementation = ErrorDto.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth,
        @Parameter(description = "삭제할 문서 ID") @PathVariable UUID id) {
        knowledge.delete(id, auth.getName(), admin(auth));
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
