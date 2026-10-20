package local.rag;

import static local.rag.ApiTypes.*;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {
    // Bounded process-wide locks: the supported deployment is one backend, not multiple instances.
    // Static ownership also protects overlapping service instances during local reconstruction.
    private static final ReentrantLock[] LOCKS=new ReentrantLock[256];
    static { for (int i=0;i<LOCKS.length;i++) LOCKS[i]=new ReentrantLock(true); }
    private final ConversationRepository repo;
    private final RagService rag;
    public ConversationService(ConversationRepository repo,RagService rag) { this.repo=repo; this.rag=rag; }
    static String uuid(String value) {
        if (value==null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ApiException.invalid("会话及请求标识必须为UUID");
        return value.toLowerCase(Locale.ROOT);
    }
    public Conversation create() { return repo.create(); }
    public Conversation get(String id) { return repo.get(uuid(id)); }
    public Answer send(String id,Message message) {
        id=uuid(id);
        if (message==null) throw ApiException.invalid("请输入问题和请求标识");
        String requestId=uuid(message.requestId());
        String question=RagService.validateQuestion(message.question());
        ReentrantLock lock=LOCKS[Math.floorMod(id.hashCode(),LOCKS.length)];
        lock.lock();
        try {
            repo.reserve(id,requestId,question);
            Answer completed=repo.completed(id,requestId);
            if (completed!=null) return completed; // Never charge a duplicate request again.
            Answer answer=rag.chat(question,repo.recent(id));
            repo.complete(id,requestId,answer);
            return answer;
        } finally { lock.unlock(); }
    }
}
