package dev.springrag.controller;

import dev.springrag.dto.AnswerDto;
import dev.springrag.dto.ConversationDto;
import dev.springrag.dto.ConversationRequest;
import dev.springrag.dto.CrawlRequest;
import dev.springrag.dto.CrawlResultDto;
import dev.springrag.dto.DocumentDto;
import dev.springrag.dto.ErrorDto;
import dev.springrag.dto.HealthDto;
import dev.springrag.dto.MeDto;
import dev.springrag.dto.MessageDto;
import dev.springrag.dto.QuestionRequest;
import dev.springrag.dto.SearchResultDto;
import dev.springrag.service.ChatService;
import dev.springrag.service.KnowledgeService;
import dev.springrag.service.OcrClient;
import dev.springrag.service.RetrievalService;
import dev.springrag.service.Reranker;
import dev.springrag.service.SiteCrawler;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final KnowledgeService knowledge;
    private final SiteCrawler crawler;
    private final ChatService chats;
    private final RetrievalService retrieval;

    public ApiController(KnowledgeService knowledge, SiteCrawler crawler, ChatService chats, RetrievalService retrieval) {
        this.knowledge = knowledge;
        this.crawler = crawler;
        this.chats = chats;
        this.retrieval = retrieval;
    }

    @GetMapping("/health") HealthDto health() { return new HealthDto("UP"); }
    @GetMapping("/me") MeDto me(Authentication auth) {
        return new MeDto(auth.getName(), admin(auth));
    }
    @GetMapping("/documents") List<DocumentDto> documents(Authentication auth, @RequestParam(defaultValue = "SHARED") String scope) {
        return knowledge.list(auth.getName(), scope);
    }
    @PostMapping("/documents") DocumentDto upload(Authentication auth, @RequestParam MultipartFile file,
                                                         @RequestParam String category) throws IOException {
        return knowledge.upload(file, category, auth.getName(), admin(auth));
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication auth, @PathVariable UUID id) {
        knowledge.delete(id, auth.getName(), admin(auth));
    }
    @PostMapping("/admin/crawl") CrawlResultDto crawl(@RequestBody CrawlRequest seed) {
        if (seed.url() == null || seed.url().isBlank()) throw new IllegalArgumentException("URL이 필요합니다");
        return crawler.crawl(seed.url(), seed.category(), seed.maxPages() == null ? 100 : seed.maxPages());
    }
    @GetMapping("/conversations") List<ConversationDto> conversations(Authentication auth) {
        return chats.list(auth.getName());
    }
    @GetMapping("/search") public SearchResultDto search(Authentication auth,
            @RequestParam String query, @RequestParam(defaultValue = "SHARED") String scope) {
        return retrieval.search(query, scope, auth.getName());
    }
    @PostMapping("/conversations") ConversationDto create(Authentication auth, @RequestBody ConversationRequest request) {
        return chats.create(auth.getName(), request.scope());
    }
    @GetMapping("/conversations/{id}/messages") List<MessageDto> messages(Authentication auth, @PathVariable UUID id) {
        return chats.messages(id, auth.getName());
    }
    @PostMapping("/conversations/{id}/messages") AnswerDto ask(Authentication auth, @PathVariable UUID id, @RequestBody QuestionRequest request) {
        return chats.ask(id, auth.getName(), request.text());
    }

    private static boolean admin(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDto> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorDto(e.getMessage()));
    }
    @ExceptionHandler(ChatService.ChatUnavailableException.class)
    ResponseEntity<ErrorDto> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("생성용 채팅 모델이 아직 설정되지 않았습니다. 관리자에게 문의해 주세요."));
    }
    @ExceptionHandler(Reranker.RerankerUnavailableException.class)
    public ResponseEntity<ErrorDto> rerankerUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("리랭커를 사용할 수 없습니다."));
    }
    @ExceptionHandler(OcrClient.OcrUnavailableException.class)
    public ResponseEntity<ErrorDto> ocrUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("OCR을 사용할 수 없습니다."));
    }
}
