package dev.springrag.chat.dao;

import dev.springrag.chat.dto.ConversationDto;
import dev.springrag.chat.dto.MessageDto;
import dev.springrag.chat.dto.SourceDto;
import dev.springrag.chat.port.ChatRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatDao implements ChatRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public ChatDao(JdbcTemplate db) { this.db = db; }

    public List<ConversationDto> list(String owner) {
        return db.query("SELECT id,title,scope FROM conversation WHERE owner_id=? ORDER BY created_at DESC",
            (rs, row) -> new ConversationDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), owner);
    }

    public ConversationDto create(String owner, String scope) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO conversation(id,owner_id,scope) VALUES (?,?,?)", id, owner, scope);
        return new ConversationDto(id, "새 대화", scope);
    }

    public List<MessageDto> messages(UUID id) {
        return db.query("SELECT role,body,sources::text FROM chat_message WHERE conversation_id=? ORDER BY id",
            (rs, row) -> new MessageDto(rs.getString(1), rs.getString(2), readSources(rs.getString(3))), id);
    }

    public List<String> history(UUID id) {
        return db.query("SELECT role || ': ' || body FROM chat_message WHERE conversation_id=? ORDER BY id DESC LIMIT 6",
            (rs, row) -> rs.getString(1), id);
    }

    public String requireOwned(UUID id, String owner) {
        List<String> scopes = db.query("SELECT scope FROM conversation WHERE id=? AND owner_id=?", (rs, row) -> rs.getString(1), id, owner);
        if (scopes.isEmpty()) throw new IllegalArgumentException("대화를 찾을 수 없습니다");
        return scopes.get(0);
    }

    public void saveAnswer(UUID id, String question, String answer, List<SourceDto> sources) {
        db.update("INSERT INTO chat_message(conversation_id,role,body) VALUES (?,'user',?)", id, question);
        db.update("INSERT INTO chat_message(conversation_id,role,body,sources) VALUES (?,'assistant',?,?::jsonb)", id, answer, writeSources(sources));
        db.update("UPDATE conversation SET title=? WHERE id=? AND title='새 대화'", question.length() > 40 ? question.substring(0, 40) + "…" : question, id);
    }

    private String writeSources(List<SourceDto> sources) {
        try { return json.writeValueAsString(sources); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private List<SourceDto> readSources(String value) {
        try { return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, SourceDto.class)); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
