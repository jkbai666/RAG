package local.rag;

import static local.rag.ApiTypes.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class RagRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public RagRepository(JdbcTemplate db, TransactionTemplate tx) { this.db = db; this.tx = tx; }
    private static final RowMapper<Document> DOCUMENT = (r,n) -> new Document(r.getString("id"),
            r.getString("file_name"), r.getString("format"), r.getLong("size_bytes"), r.getString("status"),
            r.getInt("chunk_count"), r.getTimestamp("created_at").toInstant(), r.getString("error_message"));
    private static final RowMapper<Chunk> CHUNK = (r,n) -> new Chunk(r.getString("id"),r.getString("document_id"),
            r.getInt("chunk_index"),r.getString("text"), nullableInt(r,"page_start"),nullableInt(r,"page_end"));
    private static Integer nullableInt(ResultSet r, String key) throws SQLException {
        int v = r.getInt(key); return r.wasNull() ? null : v;
    }
    public Document insertDocument(String id, String fileName, String format, long bytes) {
        Instant now = Instant.now();
        db.update("INSERT INTO rag_documents(id,file_name,format,size_bytes,status,chunk_count,created_at) VALUES(?,?,?,?, 'PROCESSING',0,?)",
                id,fileName,format,bytes,Timestamp.from(now));
        return getDocument(id);
    }
    public Document getDocument(String id) {
        return db.query("SELECT * FROM rag_documents WHERE id=?",DOCUMENT,id).stream().findFirst()
                .orElseThrow(() -> new ApiException(404,"DOCUMENT_NOT_FOUND","文档不存在"));
    }
    public List<Document> listDocuments() { return db.query("SELECT * FROM rag_documents ORDER BY created_at DESC,id DESC",DOCUMENT); }
    public long total() { return db.queryForObject("SELECT COUNT(*) FROM rag_documents",Long.class); }
    public long ready() { return db.queryForObject("SELECT COUNT(*) FROM rag_documents WHERE status='READY'",Long.class); }
    public void insertChunks(List<Chunk> chunks) {
        tx.executeWithoutResult(s -> db.batchUpdate("INSERT INTO rag_chunks(id,document_id,chunk_index,text,page_start,page_end) VALUES(?,?,?,?,?,?)",
            chunks,100,(p,c) -> { p.setString(1,c.id()); p.setString(2,c.documentId()); p.setInt(3,c.index());
                p.setString(4,c.text()); p.setObject(5,c.pageStart()); p.setObject(6,c.pageEnd()); }));
    }
    public void markReady(String id, int count) {
        int n = db.update("UPDATE rag_documents SET status='READY',chunk_count=?,error_message=NULL,cleanup_pending=FALSE WHERE id=? AND status='PROCESSING'",count,id);
        if (n != 1) throw new IllegalStateException("Invalid import transition");
    }
    public void markFailed(String id, String message) {
        db.update("UPDATE rag_documents SET status='FAILED',chunk_count=0,error_message=?,cleanup_pending=TRUE WHERE id=?", message,id);
    }
    public void recoverInterrupted() {
        db.update("UPDATE rag_documents SET status='FAILED',chunk_count=0,error_message='服务重启中断了处理，请删除后重新上传',cleanup_pending=TRUE WHERE status='PROCESSING'");
    }
    public List<String> cleanupPending() { return db.queryForList("SELECT id FROM rag_documents WHERE cleanup_pending=TRUE AND status='FAILED'",String.class); }
    public List<String> vectorIds(String id) { return db.queryForList("SELECT id FROM rag_chunks WHERE document_id=? ORDER BY chunk_index",String.class,id); }
    public List<Chunk> chunks(String id, long offset, int limit) {
        return db.query("SELECT * FROM rag_chunks WHERE document_id=? ORDER BY chunk_index LIMIT ? OFFSET ?",CHUNK,id,limit,offset);
    }
    public long chunkCount(String id) { return db.queryForObject("SELECT COUNT(*) FROM rag_chunks WHERE document_id=?",Long.class,id); }
    public Source readySource(String chunkId) {
        return db.query("SELECT c.*,d.file_name FROM rag_chunks c JOIN rag_documents d ON d.id=c.document_id WHERE c.id=? AND d.status='READY' AND d.deletion_requested=FALSE",
            (r,n) -> new Source(r.getString("id"),r.getString("document_id"),r.getString("file_name"),r.getInt("chunk_index"),
                    r.getString("text"),nullableInt(r,"page_start"),nullableInt(r,"page_end")),chunkId).stream().findFirst().orElse(null);
    }
    public void requestDeletion(String id) {
        db.update("UPDATE rag_documents SET status='FAILED',chunk_count=0,error_message='正在删除文档',cleanup_pending=TRUE,deletion_requested=TRUE WHERE id=?",id);
    }
    public void cleanupComplete(String id) {
        tx.executeWithoutResult(s -> {
            db.update("DELETE FROM rag_chunks WHERE document_id=?",id);
            db.update("UPDATE rag_documents SET cleanup_pending=FALSE WHERE id=?",id);
        });
    }
    public void deleteDocument(String id) { db.update("DELETE FROM rag_documents WHERE id=?",id); }
    public boolean deletionRequested(String id) { return Boolean.TRUE.equals(db.queryForObject("SELECT deletion_requested FROM rag_documents WHERE id=?",Boolean.class,id)); }
    public void cleanupFailed(String id) { db.update("UPDATE rag_documents SET error_message='索引清理失败，文档已停止检索；请重试删除',cleanup_pending=TRUE WHERE id=?",id); }

    // Persist an embedding identity independently of credentials. Changing models requires an empty knowledge base.
    public synchronized void ensureProfile(String profile) {
        tx.executeWithoutResult(s -> {
            List<String> existing = db.queryForList("SELECT setting_value FROM rag_settings WHERE setting_key='embedding_profile'",String.class);
            if (existing.isEmpty()) {
                db.update("INSERT INTO rag_settings(setting_key,setting_value) VALUES('embedding_profile',?)",profile);
            } else if (!existing.get(0).equals(profile)) {
                Long chunks = db.queryForObject("SELECT COUNT(*) FROM rag_chunks",Long.class);
                if (chunks != null && chunks == 0) db.update("UPDATE rag_settings SET setting_value=? WHERE setting_key='embedding_profile'",profile);
                else throw new ApiException(503,"EMBEDDING_PROFILE_MISMATCH","向量模型配置与已存索引不一致，请恢复原配置或先删除所有文档");
            }
        });
    }
}
