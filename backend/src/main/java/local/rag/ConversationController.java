package local.rag;

import static local.rag.ApiTypes.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service=service; }
    @PostMapping public Conversation create() { return service.create(); }
    @GetMapping("/{id}") public Conversation get(@PathVariable String id) { return service.get(id); }
    @PostMapping(value="/{id}/messages",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Answer send(@PathVariable String id,@RequestBody Message request) { return service.send(id,request); }
}
