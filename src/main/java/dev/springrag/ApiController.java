package dev.springrag;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
class ApiController {
    private final KnowledgeService knowledge;
    private final SiteCrawler crawler;
    private final ChatService chats;

    ApiController(KnowledgeService knowledge, SiteCrawler crawler, ChatService chats) {
        this.knowledge = knowledge;
        this.crawler = crawler;
        this.chats = chats;
    }

    record NewConversation(String scope) {}
    record Question(String text) {}
    record Seed(String url) {}

    @GetMapping("/health") Map<String, String> health() { return Map.of("status", "UP"); }
    @GetMapping("/me") Map<String, Object> me(Authentication auth) {
        return Map.of("username", auth.getName(), "admin", admin(auth));
    }
    @GetMapping("/documents") List<KnowledgeService.Item> documents(Authentication auth, @RequestParam(defaultValue = "SHARED") String scope) {
        return knowledge.list(auth.getName(), scope);
    }
    @PostMapping("/documents") KnowledgeService.Item upload(Authentication auth, @RequestParam MultipartFile file) throws IOException {
        return knowledge.upload(file, auth.getName(), admin(auth));
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Authentication auth, @PathVariable UUID id) {
        knowledge.delete(id, auth.getName(), admin(auth));
    }
    @PostMapping("/admin/crawl") SiteCrawler.Result crawl(@RequestBody Seed seed) {
        if (seed.url() == null || seed.url().isBlank()) throw new IllegalArgumentException("URL이 필요합니다");
        return crawler.crawl(seed.url());
    }
    @GetMapping("/conversations") List<ChatService.Conversation> conversations(Authentication auth) {
        return chats.list(auth.getName());
    }
    @PostMapping("/conversations") ChatService.Conversation create(Authentication auth, @RequestBody NewConversation request) {
        return chats.create(auth.getName(), request.scope());
    }
    @GetMapping("/conversations/{id}/messages") List<ChatService.Message> messages(Authentication auth, @PathVariable UUID id) {
        return chats.messages(id, auth.getName());
    }
    @PostMapping("/conversations/{id}/messages") ChatService.Answer ask(Authentication auth, @PathVariable UUID id, @RequestBody Question request) {
        return chats.ask(id, auth.getName(), request.text());
    }

    private static boolean admin(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(ChatService.ChatUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "생성용 채팅 모델이 아직 설정되지 않았습니다. 관리자에게 문의해 주세요."));
    }
}
