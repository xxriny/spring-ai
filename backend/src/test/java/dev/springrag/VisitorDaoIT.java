package dev.springrag;

import dev.springrag.chat.dao.VisitorDao;
import dev.springrag.chat.service.VisitorRateLimitException;
import dev.springrag.chat.service.VisitorUnauthorizedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorDaoIT {
    @Test @DisplayName("만료된 방문자 토큰은 거절하고 토큰 원문은 저장하지 않는다")
    void rejectsExpiredTokenWithoutStoringRawToken() {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var db = new JdbcTemplate(dataSource);
            createTables(db);
            var visitors = new VisitorDao(db, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            UUID id = UUID.randomUUID();
            visitors.create(id, "hash-only", Instant.now().plusSeconds(3600));

            assertEquals(id, visitors.requireActive("hash-only"));
            assertEquals(0L, db.queryForObject("SELECT count(*) FROM visitor_session WHERE token_hash='raw-token'", Long.class));
            db.update("UPDATE visitor_session SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), id);
            assertThrows(VisitorUnauthorizedException.class, () -> visitors.requireActive("hash-only"));
            assertThrows(VisitorUnauthorizedException.class, () -> visitors.charge(id));
        }
    }

    @Test @DisplayName("동시 요청에도 방문자 5회·전체 30회 한도를 원자적으로 지킨다")
    void chargesAtomicallyBeforePaidCalls() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var db = new JdbcTemplate(dataSource);
            createTables(db);
            var visitors = new VisitorDao(db, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            UUID one = UUID.randomUUID();
            visitors.create(one, "one-hash", Instant.now().plusSeconds(3600));
            var threads = Executors.newFixedThreadPool(10);
            try {
                var calls = IntStream.range(0, 10).<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> {
                    try { visitors.charge(one); return true; }
                    catch (VisitorRateLimitException e) { return false; }
                }).toList();
                int passed = 0;
                for (Future<Boolean> result : threads.invokeAll(calls)) if (result.get()) passed++;
                assertEquals(5, passed);
            } finally {
                threads.shutdownNow();
            }
            assertEquals(5, db.queryForObject("SELECT used FROM visitor_daily_usage WHERE visitor_id=?", Integer.class, one));
            assertEquals(5, db.queryForObject("SELECT used FROM global_daily_usage", Integer.class));
            for (int i = 0; i < 25; i++) {
                UUID id = UUID.randomUUID();
                visitors.create(id, "hash-" + i, Instant.now().plusSeconds(3600));
                visitors.charge(id);
            }
            UUID last = UUID.randomUUID();
            visitors.create(last, "last-hash", Instant.now().plusSeconds(3600));
            assertThrows(VisitorRateLimitException.class, () -> visitors.charge(last));
            assertEquals(30, db.queryForObject("SELECT used FROM global_daily_usage", Integer.class));
            assertTrue(db.queryForObject("SELECT count(*) FROM visitor_daily_usage WHERE visitor_id=?", Long.class, last) == 0);
        }
    }

    private static void createTables(JdbcTemplate db) {
        db.execute("CREATE TABLE visitor_session(id UUID PRIMARY KEY, token_hash CHAR(64) UNIQUE NOT NULL, expires_at TIMESTAMPTZ NOT NULL)");
        db.execute("CREATE TABLE visitor_daily_usage(usage_day DATE NOT NULL, visitor_id UUID NOT NULL REFERENCES visitor_session(id), used INT NOT NULL, PRIMARY KEY(usage_day,visitor_id))");
        db.execute("CREATE TABLE global_daily_usage(usage_day DATE PRIMARY KEY, used INT NOT NULL)");
    }
}
