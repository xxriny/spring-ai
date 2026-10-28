package dev.springrag.chat.dao;

import dev.springrag.chat.port.VisitorRepository;
import dev.springrag.chat.service.VisitorRateLimitException;
import dev.springrag.chat.service.VisitorUnauthorizedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 방문자 세션과 일일 사용량을 PostgreSQL에 저장한다. */
@Repository
@Profile("gcp")
public class VisitorDao implements VisitorRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    /**
     * 방문자 저장소를 연결한다.
     * @param db PostgreSQL 연결
     * @param transaction 일일 한도의 원자적 갱신 트랜잭션
     */
    public VisitorDao(JdbcTemplate db, TransactionTemplate transaction) {
        this.db = db;
        this.transaction = transaction;
    }

    @Override public void create(UUID id, String tokenHash, Instant expiresAt) {
        db.update("INSERT INTO visitor_session(id,token_hash,expires_at) VALUES (?,?,?)",
            id, tokenHash, Timestamp.from(expiresAt));
    }

    @Override public UUID requireActive(String tokenHash) {
        var ids = db.query("SELECT id FROM visitor_session WHERE token_hash=? AND expires_at>now()",
            (rs, row) -> rs.getObject(1, UUID.class), tokenHash);
        if (ids.isEmpty()) throw new VisitorUnauthorizedException();
        return ids.get(0);
    }

    @Override public void charge(UUID visitorId) {
        transaction.executeWithoutResult(status -> {
            Integer active = db.queryForObject(
                "SELECT count(*) FROM visitor_session WHERE id=? AND expires_at>now()", Integer.class, visitorId);
            if (active == null || active == 0) throw new VisitorUnauthorizedException();
            var global = db.query("INSERT INTO global_daily_usage(usage_day,used) VALUES ((now() AT TIME ZONE 'UTC')::date,1) "
                + "ON CONFLICT (usage_day) DO UPDATE SET used=global_daily_usage.used+1 "
                + "WHERE global_daily_usage.used<30 RETURNING used", (rs, row) -> rs.getInt(1));
            if (global.isEmpty()) throw new VisitorRateLimitException();
            var visitor = db.query("INSERT INTO visitor_daily_usage(usage_day,visitor_id,used) "
                + "VALUES ((now() AT TIME ZONE 'UTC')::date,?,1) "
                + "ON CONFLICT (usage_day,visitor_id) DO UPDATE SET used=visitor_daily_usage.used+1 "
                + "WHERE visitor_daily_usage.used<5 RETURNING used", (rs, row) -> rs.getInt(1), visitorId);
            if (visitor.isEmpty()) throw new VisitorRateLimitException();
        });
    }
}
