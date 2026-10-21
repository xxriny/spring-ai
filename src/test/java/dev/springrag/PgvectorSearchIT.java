package dev.springrag;

import dev.springrag.dao.KnowledgeDao;
import dev.springrag.service.KnowledgeService;
import dev.springrag.service.RetrievalService;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

class PgvectorSearchIT {
    @TempDir Path tempDir;
    @Test void pgvectorFiltersPrivateAndSharedChunks() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")) {
            postgres.start();
            var datasource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(datasource);
            var store = PgVectorStore.builder(new JdbcTemplate(datasource), embeddings())
                .dimensions(1536).initializeSchema(true).build();
            store.afterPropertiesSet();
            new KnowledgeDao(new JdbcTemplate(datasource)).backfillVectorCategories();
            var shared = new Document("shared answer", Map.of("scope", "SHARED", "ownerId", "", "documentId", "shared"));
            var alice = new Document("alice answer", Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "alice"));
            var bob = new Document("bob answer", Map.of("scope", "PRIVATE", "ownerId", "bob", "documentId", "bob"));
            store.add(List.of(shared, alice, bob));
            var retrieval = new RetrievalService(store, (query, passages) -> passages.stream().map(p -> 1.0).toList());
            var privateHits = retrieval.search("answer", "PRIVATE", "alice").candidates();
            assertEquals(1, privateHits.size());
            assertEquals("alice", privateHits.get(0).documentId());
            var sharedHits = retrieval.search("answer", "SHARED", "alice").candidates();
            assertEquals(1, sharedHits.size());
            assertEquals("shared", sharedHits.get(0).documentId());
            store.delete(List.of(shared.getId()));
            assertTrue(retrieval.search("answer", "SHARED", "alice").candidates().isEmpty());

            var jdbc = new JdbcTemplate(datasource);
            var repository = new KnowledgeDao(jdbc);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
            var knowledge = new KnowledgeService(repository, store, tx, tempDir.toString());
            var old = knowledge.ingestUrl("old knowledge".getBytes(), "source.txt", "https://docs.example.org/source.txt", "Guide");
            var oldChunks = repository.chunkIds(old.id());
            assertFalse(oldChunks.isEmpty());
            jdbc.update("UPDATE vector_store SET metadata = metadata::jsonb - 'category' WHERE id=?::uuid", UUID.fromString(oldChunks.get(0)));
            repository.backfillVectorCategories();
            assertEquals("Guide", repository.findUrl("SHARED", old.sourceUrl()).category());
            assertEquals("uncategorized", jdbc.queryForObject(
                "SELECT metadata->>'category' FROM vector_store WHERE id=?::uuid", String.class, UUID.fromString(oldChunks.get(0))));
            var failing = new KnowledgeDao(jdbc) {
                @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                    String path, String hash, String category, List<String> chunkIds) {
                    throw new IllegalStateException("simulated catalogue failure");
                }
            };
            assertThrows(IllegalStateException.class, () -> new KnowledgeService(failing, store, tx, tempDir.toString())
                .ingestUrl("new knowledge".getBytes(), "source.txt", old.sourceUrl(), "Guide"));
            assertEquals(oldChunks, repository.chunkIds(old.id()));
            assertTrue(retrieval.search("answer", "SHARED", "alice").candidates().stream()
                .anyMatch(hit -> hit.documentId().equals(old.id().toString()) && hit.text().contains("old knowledge")));
            VectorStore failingDelete = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
                new Class<?>[] {VectorStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("add")) { store.add((List<Document>) args[0]); return null; }
                    if (method.getName().equals("delete")) {
                        if (oldChunks.equals(args[0])) throw new IllegalStateException("simulated old vector deletion failure");
                        store.delete((List<String>) args[0]);
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
            assertThrows(IllegalStateException.class, () -> new KnowledgeService(repository, failingDelete, tx, tempDir.toString())
                .ingestUrl("newer knowledge".getBytes(), "source.txt", old.sourceUrl(), "Guide"));
            assertEquals(oldChunks, repository.chunkIds(old.id()));
            assertTrue(retrieval.search("answer", "SHARED", "alice").candidates().stream()
                .anyMatch(hit -> hit.documentId().equals(old.id().toString()) && hit.text().contains("old knowledge")));
            try (var app = new SpringApplicationBuilder(SpringRagApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.ai.openai.api-key=unused-test-key", "--app.admin-password=test-admin",
                        "--app.user-password=test-user", "--server.port=0")) {
                assertNotNull(app.getBean(RetrievalService.class));
                int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
                var client = HttpClient.newHttpClient();
                var unauthenticated = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/search?query=test"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(401, unauthenticated.statusCode());
                String basic = Base64.getEncoder().encodeToString("user:test-user".getBytes());
                var blank = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/search?query=&scope=PRIVATE"))
                    .header("Authorization", "Basic " + basic).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(400, blank.statusCode());
            }
        }
    }

    private static EmbeddingModel embeddings() {
        return (EmbeddingModel) Proxy.newProxyInstance(EmbeddingModel.class.getClassLoader(), new Class<?>[] {EmbeddingModel.class},
            (proxy, method, args) -> {
                if (method.getName().equals("dimensions")) return 1536;
                if (method.getName().equals("embed")) {
                    if (args[0] instanceof List<?> list) return list.stream().map(item -> vector()).toList();
                    return vector();
                }
                throw new AssertionError(method.getName());
            });
    }

    private static float[] vector() {
        float[] embedding = new float[1536];
        embedding[0] = 1;
        return embedding;
    }
}
