package dev.springrag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.springrag.knowledge.port.KnowledgeRepository;
import dev.springrag.crawl.dto.CrawlRequest;
import dev.springrag.retrieval.dto.SearchResultDto;
import dev.springrag.knowledge.service.KnowledgeService;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class LayerStructureTest {
    @Test @DisplayName("대화·문서·검색·수집은 각 업무 패키지의 공개 서비스로 분리된다")
    void businessModulesHaveOwnEntryPoints() {
        for (String name : new String[] {
            "dev.springrag.chat.service.ChatService",
            "dev.springrag.knowledge.service.KnowledgeService",
            "dev.springrag.retrieval.service.RetrievalService",
            "dev.springrag.crawl.service.SiteCrawler"
        }) assertDoesNotThrow(() -> Class.forName(name), name);
    }

    @Test @DisplayName("서비스는 JDBC 구현 대신 각 업무의 저장 포트에 의존한다")
    void servicesUseRepositoryPorts() throws Exception {
        assertEquals("dev.springrag.chat.port.ChatRepository",
            Class.forName("dev.springrag.chat.service.ChatService").getDeclaredField("repository").getType().getName());
        assertEquals("dev.springrag.knowledge.port.KnowledgeRepository",
            Class.forName("dev.springrag.knowledge.service.KnowledgeService").getDeclaredField("repository").getType().getName());
        assertEquals("dev.springrag.retrieval.port.RetrievalDao",
            Class.forName("dev.springrag.retrieval.service.RetrievalService").getDeclaredField("dao").getType().getName());
    }

    @Test @DisplayName("OCR·PDF 추출·리랭커는 업무 모듈의 외부 호출 포트에 의존한다")
    void externalClientsUseModulePorts() throws Exception {
        assertEquals("dev.springrag.knowledge.port.OcrClient",
            KnowledgeService.class.getDeclaredField("ocr").getType().getName());
        assertEquals("dev.springrag.knowledge.port.PdfExtractionClient",
            KnowledgeService.class.getDeclaredField("pdfExtraction").getType().getName());
        assertEquals("dev.springrag.retrieval.port.Reranker",
            Class.forName("dev.springrag.retrieval.service.RetrievalService").getDeclaredField("reranker").getType().getName());
    }

    @Test @DisplayName("문서 색인과 검색 서비스는 공통 벡터 포트에 의존한다")
    void vectorAccessUsesAdapterPort() throws Exception {
        assertEquals("dev.springrag.common.port.VectorIndex",
            KnowledgeService.class.getDeclaredField("vectors").getType().getName());
        assertEquals("dev.springrag.common.port.VectorIndex",
            Class.forName("dev.springrag.retrieval.service.RetrievalService").getDeclaredField("vectors").getType().getName());
    }

    @Test @DisplayName("백엔드 테스트 클래스는 backend Maven 모듈에서 실행된다")
    void backendIsSeparateMavenModule() throws URISyntaxException {
        Path classes = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals("backend", classes.getParent().getParent().getFileName().toString());
    }

    @Test @DisplayName("서비스별 컨트롤러와 DTO·서비스·DAO 계층을 유지한다")
    void mvcAndLayeredPackagesExist() throws Exception {
        for (String name : new String[] {
            "dev.springrag.common.controller.SystemController",
            "dev.springrag.knowledge.controller.KnowledgeController",
            "dev.springrag.crawl.controller.CrawlController",
            "dev.springrag.retrieval.controller.SearchController",
            "dev.springrag.chat.controller.ChatController",
            "dev.springrag.common.controller.ApiExceptionHandler",
            "dev.springrag.crawl.dto.CrawlRequest",
            "dev.springrag.retrieval.dto.SearchResultDto",
            "dev.springrag.retrieval.service.RetrievalService",
            "dev.springrag.knowledge.dao.KnowledgeDao"
        }) {
            assertDoesNotThrow(() -> Class.forName(name), name);
        }
        assertTrue(CrawlRequest.class.isRecord());
        assertTrue(SearchResultDto.class.isRecord());
        assertEquals(KnowledgeRepository.class, KnowledgeService.class.getDeclaredField("repository").getType());
    }
}
