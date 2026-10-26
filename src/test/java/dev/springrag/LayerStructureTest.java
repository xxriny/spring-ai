package dev.springrag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.springrag.dao.KnowledgeDao;
import dev.springrag.dto.CrawlRequest;
import dev.springrag.dto.SearchResultDto;
import dev.springrag.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class LayerStructureTest {
    @Test @DisplayName("서비스별 컨트롤러와 DTO·서비스·DAO 계층을 유지한다")
    void mvcAndLayeredPackagesExist() throws Exception {
        for (String name : new String[] {
            "dev.springrag.controller.SystemController",
            "dev.springrag.controller.KnowledgeController",
            "dev.springrag.controller.CrawlController",
            "dev.springrag.controller.SearchController",
            "dev.springrag.controller.ChatController",
            "dev.springrag.controller.ApiExceptionHandler",
            "dev.springrag.dto.CrawlRequest",
            "dev.springrag.dto.SearchResultDto",
            "dev.springrag.service.RetrievalService",
            "dev.springrag.dao.KnowledgeDao"
        }) {
            assertDoesNotThrow(() -> Class.forName(name), name);
        }
        assertTrue(CrawlRequest.class.isRecord());
        assertTrue(SearchResultDto.class.isRecord());
        assertEquals(KnowledgeDao.class, KnowledgeService.class.getDeclaredField("repository").getType());
    }
}
