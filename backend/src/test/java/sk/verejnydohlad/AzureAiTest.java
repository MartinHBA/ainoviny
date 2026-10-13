package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class AzureAiTest {
    static Config config() { return Config.from(Map.of("AI_MODE","azure","AZURE_OPENAI_ENDPOINT","https://test.openai.azure.com/",
        "AZURE_OPENAI_DEPLOYMENT","writer-mini","AZURE_OPENAI_EDITOR_DEPLOYMENT","chief-editor","AZURE_OPENAI_API_KEY","offline-test-placeholder")); }
    static String response(String content,String finish,String refusal) {
        var message=new HashMap<String,Object>();message.put("content",content);message.put("refusal",refusal);
        return Json.write(Map.of("choices",List.of(Map.of("finish_reason",finish,"message",message)),"usage",Map.of("prompt_tokens",20,"completion_tokens",10)));
    }
    @Test void editorUsesItsOwnDeploymentAndStrictSchemaWithoutSharingWriterConversation() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var r=Fixtures.release("azure-contract");var d=new MockAi().generate(List.of(r));
        http.payload=response(Json.write(new EditorDecision(true,false,List.of(),"Approved synthetic request contract.")),"stop",null);
        assertTrue(ai.review(d,List.of(r)).approved());var body=Json.tree(http.requestBody());
        assertEquals("chief-editor",body.path("model").asText());assertEquals("editor",body.path("response_format").path("json_schema").path("name").asText());
        assertTrue(body.path("response_format").path("json_schema").path("strict").asBoolean());
        assertEquals(2,body.path("messages").size());assertTrue(body.path("messages").get(0).path("content").asText().contains("final chief editor"));
        assertEquals("https://test.openai.azure.com/openai/v1/chat/completions",http.request.uri().toString());
        assertTrue(body.path("max_tokens").asInt()<=1600);
        http.payload=response(Json.write(new Verification(true,false,List.of())),"stop",null);assertTrue(ai.verify(d,List.of(r)).passed());
        assertEquals("writer-mini",Json.tree(http.requestBody()).path("model").asText());
    }
    @Test void refusedTruncatedMalformedAndContradictoryEditorResponsesAreRejected() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var r=Fixtures.release("bad-contract");var d=new MockAi().generate(List.of(r));
        String valid=Json.write(new EditorDecision(true,false,List.of(),"Approved synthetic request contract."));
        for(String payload:List.of(response(valid,"length",null),response(valid,"stop","refused"),response("{}","stop",null),
            response(Json.write(new EditorDecision(true,true,List.of(),"Contradictory sensitive approval.")),"stop",null))) {
            http.payload=payload;assertThrows(IllegalArgumentException.class,() -> ai.review(d,List.of(r)));
        }
    }
    @Test void cleanApprovalsAcceptEmptyOrShortReasonsButRejectionsRequireAnExplanation() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var r=Fixtures.release("concise-approval");var d=new MockAi().generate(List.of(r));
        for(String reason:List.of("","OK")) {
            http.payload=response(Json.write(new EditorDecision(true,false,List.of(),reason)),"stop",null);
            assertTrue(ai.review(d,List.of(r)).approved());
        }
        for(var decision:List.of(new EditorDecision(false,false,List.of(),""),new EditorDecision(true,true,List.of(),""),
            new EditorDecision(true,false,List.of("Unsupported claim."),""))) {
            http.payload=response(Json.write(decision),"stop",null);
            assertThrows(IllegalArgumentException.class,() -> ai.review(d,List.of(r)));
        }
    }
    @Test void finalEditorReceivesFullOriginalAndOversizedInputsFailBeforeHttp() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var r=Fixtures.release("full-original");
        String body=r.body()+"x".repeat(11000)+" A caveat at the end of the full original.";
        var full=new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt(),r.collectedAt(),r.title(),body,Normalize.hash(body),r.blobPath());
        var draft=new MockAi().generate(List.of(r));http.payload=response(Json.write(new EditorDecision(true,false,List.of(),"Synthetic complete-source contract.")),"stop",null);
        ai.review(draft,List.of(full));var input=Json.tree(Json.tree(http.requestBody()).path("messages").get(1).path("content").asText());
        assertEquals(body,input.path("sources").get(0).path("body").asText());
        var huge=new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt(),r.collectedAt(),r.title(),"x".repeat(200001),r.contentHash(),r.blobPath());
        http.request=null;assertThrows(IllegalArgumentException.class,() -> ai.review(draft,List.of(huge)));assertNull(http.request);
    }
    @Test void generatedPublicationTextAndEvidenceMapCannotDiverge() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var r=Fixtures.release("single-copy-generation");
        var original=new MockAi().generate(List.of(r));
        var mapped=original.claims().stream().map(c -> new GenerationClaim(c.text(),c.sourceId(),"body-0")).toList();
        var generation=new Generation(mapped.get(0),mapped.get(1),original.category(),mapped.subList(2,mapped.size()));
        http.payload=response(Json.write(generation),"stop",null);var draft=ai.generate(List.of(r));
        assertEquals(original,draft);assertTrue(Validation.grounded(draft,List.of(r),new Verification(true,false,List.of())).passed());
        assertEquals("writer-mini",Json.tree(http.requestBody()).path("model").asText());
    }
    @Test void invalidGeneratedParagraphBoundariesAreNotPublished() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var r=Fixtures.release("bad-paragraphs");var d=new MockAi().generate(List.of(r));
        for(var paragraphs:List.of(List.<GenerationClaim>of(),List.of(new GenerationClaim(d.markdown()+"\n\nUnmapped extra paragraph.",r.id(),"body-0")))) {
            http.payload=response(Json.write(new Generation(new GenerationClaim(d.title(),r.id(),"body-0"),new GenerationClaim(d.perex(),r.id(),"body-0"),d.category(),paragraphs)),"stop",null);
            assertThrows(IllegalArgumentException.class,() -> ai.generate(List.of(r)));
        }
    }
    @Test void analystUsesStrongDeploymentAndWriterNeverReceivesPrivateLeads() {
        var http=new OfflineHttp();var ai=new AzureAi(config(),http);var a=Fixtures.release("research-contract");
        var b=ResearchTest.document("research-contract-second","second","[SYNTHETIC] Second document",a.body()+" Second document.");
        var sources=List.of(a,b);var brief=ResearchTest.brief(sources,false);
        http.payload=response(Json.write(brief),"stop",null);assertEquals(brief,ai.research(sources));
        var request=Json.tree(http.requestBody());assertEquals("chief-editor",request.path("model").asText());
        assertEquals("research",request.path("response_format").path("json_schema").path("name").asText());
        var draft=new MockAi().generate(List.of(a));var claims=draft.claims().stream().map(c -> new GenerationClaim(c.text(),c.sourceId(),"body-0")).toList();
        http.payload=response(Json.write(new Generation(claims.get(0),claims.get(1),draft.category(),claims.subList(2,claims.size()))),"stop",null);
        ai.generate(sources,brief);var input=Json.tree(Json.tree(http.requestBody()).path("messages").get(1).path("content").asText());
        assertFalse(input.path("research").has("leads"));assertEquals(2,input.path("sources").size());
        assertFalse(http.requestBody().contains("Private unanswered question."));
    }
    // No network or Azure credential calls; only the adapter request/response contract is exercised.
    static class OfflineHttp extends HttpClient {
        HttpRequest request;String payload;
        String requestBody() {
            var bytes=new java.io.ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer b) { byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data); }
                public void onError(Throwable t) { throw new IllegalStateException(t); }
                public void onComplete() {}
            });return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> handler) {
            request=r;byte[] data=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpResponse<byte[]> response=new HttpResponse<>() {
                public int statusCode(){return 200;}public HttpRequest request(){return r;}public Optional<HttpResponse<byte[]>> previousResponse(){return Optional.empty();}
                public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public byte[] body(){return data;}
                public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return r.uri();}public Version version(){return Version.HTTP_1_1;}
            };return (HttpResponse<T>)(HttpResponse<?>)response;
        }
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.empty();}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new IllegalStateException(e);}}
        public SSLParameters sslParameters(){return new SSLParameters();}public Optional<Authenticator> authenticator(){return Optional.empty();}
        public Version version(){return Version.HTTP_1_1;}public Optional<Executor> executor(){return Optional.empty();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
    }
}
