package dev.springrag;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.springrag.dao.KnowledgeDao;
import dev.springrag.dto.CrawlRequest;
import dev.springrag.dto.SearchResultDto;
import dev.springrag.service.KnowledgeService;
import org.junit.jupiter.api.Test;

class LayerStructureTest {
    @Test void mvcAndLayeredPackagesExist() throws Exception {
        for (String name : new String[] {
            "dev.springrag.controller.ApiController",
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
