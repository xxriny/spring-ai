package dev.springrag.dao;

import java.util.List;
import org.springframework.ai.document.Document;

public interface RetrievalDao {
    List<Document> bm25(String query, String scope, String owner, int limit);
    List<Document> neighbors(Document anchor, String scope, String owner);
}
