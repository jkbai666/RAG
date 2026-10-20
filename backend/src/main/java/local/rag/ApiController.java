package local.rag;

import static local.rag.ApiTypes.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final RagService service;
    public ApiController(RagService service) { this.service=service; }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status","UP"); }
    @GetMapping("/knowledge/status") public KnowledgeStatus knowledge() { return service.knowledge(); }
    @GetMapping("/documents") public Documents documents() { return service.documents(); }
    @GetMapping("/documents/{id}") public Document document(@PathVariable String id) { return service.document(id); }
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Document upload(@RequestPart("file") MultipartFile file,MultipartHttpServletRequest request) {
        int count=request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum();
        if (count!=1) throw ApiException.invalid("每次只允许上传一个文件");
        return service.upload(file);
    }
    @GetMapping("/documents/{id}/chunks")
    public Chunks chunks(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return service.chunks(id,page,pageSize);
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.delete(id); }
    @PostMapping(value="/chat",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Answer chat(@RequestBody Question request) {
        if (request==null) throw ApiException.invalid("请输入问题");
        return service.chat(request.question());
    }
}
