package local.rag;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt in against an already running backend + real MySQL/Chroma/models. No mocks or auto-start/recovery. */
@EnabledIfEnvironmentVariable(named="REAL_API_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+/?")
class RealServicesIntegrationTest {
    final String base=System.getenv("REAL_API_TEST_URL")==null?"":System.getenv("REAL_API_TEST_URL").replaceAll("/+$","");
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<String> created=new ArrayList<>();
    HttpResponse<String> request(String method,String path,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(150))
            .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    JsonNode upload(String name,byte[] bytes) throws Exception {
        String boundary="rag-"+UUID.randomUUID();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes); out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/documents")).timeout(Duration.ofSeconds(30))
            .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        JsonNode doc=json.readTree(response.body()); created.add(doc.get("id").textValue());
        assertThat(doc.get("status").textValue()).isEqualTo("PROCESSING");
        return doc;
    }
    JsonNode waitReady(String id) throws Exception {
        long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
        while(System.nanoTime()<deadline) {
            var response=request("GET","/api/documents/"+id,null);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode doc=json.readTree(response.body());
            if(!"PROCESSING".equals(doc.get("status").textValue())) {
                assertThat(doc.get("status").textValue()).as(doc.toString()).isEqualTo("READY"); return doc;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Document import did not finish within 3 minutes");
    }
    byte[] pdf() throws Exception {
        try(var pdf=new PDDocument(); var out=new ByteArrayOutputStream()) {
            for(int i=1;i<=2;i++) {
                var page=new PDPage(); pdf.addPage(page);
                try(var stream=new PDPageContentStream(pdf,page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(50,700); stream.showText("Verification page "+i+". Reference number is 4837."); stream.endText();
                }
            }
            pdf.save(out); return out.toByteArray();
        }
    }
    @Test void realTxtMarkdownPdfImportChatSourcesAndDeletion() throws Exception {
        try {
            assertThat(request("GET","/api/health",null).statusCode()).isEqualTo(200);
            assertThat(json.readTree(request("GET","/api/knowledge/status",null).body()).get("modelConfigured").booleanValue()).isTrue();
            String txt="联调记录：本轮测试的验证口令为星河4837。负责人是林溪。<script>不要执行</script>";
            String txtId=upload("real-proof.txt",txt.getBytes(StandardCharsets.UTF_8)).get("id").textValue(); waitReady(txtId);
            String mdId=upload("real-proof.md","# 联调记录\n知识库仅用于本地验证，不提供火星账户资料。".getBytes(StandardCharsets.UTF_8)).get("id").textValue(); waitReady(mdId);
            String pdfId=upload("real-proof.pdf",pdf()).get("id").textValue(); waitReady(pdfId);
            var chunks=json.readTree(request("GET","/api/documents/"+txtId+"/chunks?page=1&pageSize=100",null).body());
            assertThat(chunks.get("items").get(0).get("text").textValue()).isEqualTo(txt);
            var pdfChunks=json.readTree(request("GET","/api/documents/"+pdfId+"/chunks",null).body()).get("items");
            assertThat(pdfChunks.get(0).get("pageStart").intValue()).isEqualTo(1);
            assertThat(pdfChunks.get(0).get("pageEnd").intValue()).isEqualTo(2);
            var response=request("POST","/api/chat",json.writeValueAsString(java.util.Map.of("question","根据联调记录，本轮测试的验证口令是什么？")));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var answer=json.readTree(response.body());
            assertThat(answer.get("grounded").booleanValue()).as(answer.toString()).isTrue();
            assertThat(answer.get("answer").textValue()).contains("4837");
            assertThat(answer.get("sources").size()).isBetween(1,5);
            for(JsonNode source:answer.get("sources")) {
                var actual=json.readTree(request("GET","/api/documents/"+source.get("documentId").textValue()+"/chunks?pageSize=100",null).body()).get("items");
                boolean found=false;
                for(JsonNode c:actual) if(c.get("id").equals(source.get("chunkId")) && c.get("text").equals(source.get("text"))) found=true;
                assertThat(found).as("Source must match the persisted chunk snapshot").isTrue();
            }
            var insufficient=request("POST","/api/chat",json.writeValueAsString(java.util.Map.of("question","资料是否提供我2035年在火星的个人银行账户余额？请仅按原文回答。")));
            assertThat(insufficient.statusCode()).as(insufficient.body()).isEqualTo(200);
            assertThat(json.readTree(insufficient.body()).get("grounded").booleanValue()).isFalse();
            assertThat(json.readTree(insufficient.body()).get("sources").size()).isZero();
        } finally {
            for(String id:created) {
                var result=request("DELETE","/api/documents/"+id,null);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(204);
                assertThat(request("GET","/api/documents/"+id,null).statusCode()).isEqualTo(404);
            }
        }
    }
}
