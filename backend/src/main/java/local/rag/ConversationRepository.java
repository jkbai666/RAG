package local.rag;

import static local.rag.ApiTypes.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ConversationRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public ConversationRepository(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) {
        this.db=db; this.tx=tx; this.json=json;
    }
    public Conversation create() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO rag_conversations(id,created_at) VALUES(?,?)",id,Timestamp.from(Instant.now()));
        return new Conversation(id,List.of());
    }
    void require(String id) {
        if (db.queryForObject("SELECT COUNT(*) FROM rag_conversations WHERE id=?",Long.class,id)==0)
            throw new ApiException(404,"CONVERSATION_NOT_FOUND","会话不存在，请重试恢复或明确选择新对话");
    }
    public Conversation get(String id) {
        require(id);
        return new Conversation(id,db.query("SELECT request_id,question,response_json,created_at FROM rag_conversation_exchanges WHERE conversation_id=? AND response_json IS NOT NULL ORDER BY created_at,sequence_id",
            (rs,n) -> new Exchange(rs.getString(1),rs.getString(2),decode(rs.getString(3)),rs.getTimestamp(4).toInstant()),id));
    }
    // A failed attempt retains its identity, but never appears in successful history.
    void reserve(String id,String requestId,String question) {
        tx.executeWithoutResult(status -> {
            require(id);
            List<String> existing=db.queryForList("SELECT question FROM rag_conversation_exchanges WHERE conversation_id=? AND request_id=?",String.class,id,requestId);
            if (existing.isEmpty()) db.update("INSERT INTO rag_conversation_exchanges(conversation_id,request_id,question) VALUES(?,?,?)",id,requestId,question);
            else if (!existing.get(0).equals(question)) throw new ApiException(409,"REQUEST_ID_CONFLICT","该请求标识已用于其他问题，请为新问题使用新标识");
        });
    }
    Answer completed(String id,String requestId) {
        List<String> rows=db.queryForList("SELECT response_json FROM rag_conversation_exchanges WHERE conversation_id=? AND request_id=?",String.class,id,requestId);
        return rows.isEmpty() || rows.get(0)==null ? null : decode(rows.get(0));
    }
    List<HistoryTurn> recent(String id) {
        List<HistoryTurn> history=new ArrayList<>(db.query("SELECT question,response_json FROM rag_conversation_exchanges WHERE conversation_id=? AND response_json IS NOT NULL ORDER BY created_at DESC,sequence_id DESC LIMIT ?",
            (rs,n) -> new HistoryTurn(rs.getString(1),decode(rs.getString(2)).answer()),id,RagService.HISTORY_LIMIT));
        Collections.reverse(history);
        return history;
    }
    void complete(String id,String requestId,Answer answer) {
        try {
            String body=json.writeValueAsString(answer);
            int changed=db.update("UPDATE rag_conversation_exchanges SET response_json=?,created_at=? WHERE conversation_id=? AND request_id=? AND response_json IS NULL",
                body,Timestamp.from(Instant.now()),id,requestId);
            if (changed!=1) throw new IllegalStateException("Conversation completion was not unique");
        } catch (JsonProcessingException e) { throw new IllegalStateException("Answer serialization failed"); }
    }
    private Answer decode(String value) {
        try { return json.readValue(value,Answer.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored answer could not be read"); }
    }
}
