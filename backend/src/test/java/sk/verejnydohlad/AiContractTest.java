package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class AiContractTest {
    @Test void malformedMissingAndUnexpectedAiFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> Json.read("{\"passed\":true,\"issues\":[]}",Verification.class));
        assertThrows(IllegalArgumentException.class,() -> Json.read("{\"passed\":true,\"sensitive\":false,\"issues\":[],\"publish\":true}",Verification.class));
        assertThrows(IllegalArgumentException.class,() -> Json.read("{\"passed\":null,\"sensitive\":false,\"issues\":[]}",Verification.class));
        assertThrows(IllegalArgumentException.class,() -> Json.read("{\"passed\":true,\"sensitive\":false,\"issues\":[]} {}",Verification.class));
    }
    @Test void invalidRankingsAndUnboundedSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> Validation.analysis(new Analysis(101,"test","Slovensko","invalid score")));
        assertThrows(IllegalArgumentException.class,() -> Validation.analysis(new Analysis(80,"test","invented category","invalid category")));
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("MAX_ARTICLES_PER_DAY","9")));
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("MAX_PUBLISHED_ARTICLES_PER_DAY","6")));
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("AI_MODE","azure","AZURE_OPENAI_ENDPOINT","http://insecure.invalid")));
    }
    @Test void generatedDraftWithUnsupportedMarkupIsNotClearToApprove() {
        var r=Fixtures.release("markup");var d=new MockAi().generate(List.of(r));
        var malicious=new Draft(d.title(),d.perex(),d.category(),d.markdown()+"\n\n<script>alert(1)</script>",d.claims());
        assertFalse(Validation.grounded(malicious,List.of(r),new Verification(true,false,List.of())).passed());
    }
    @Test void allStructuredSchemasRejectAdditionalProperties() throws Exception {
        for(String name:List.of("analysis","draft","verification","editor","research","research-plan","researched-draft")) {
            try(var stream=getClass().getResourceAsStream("/schemas/"+name+".json")) {
                assertNotNull(stream);var schema=Json.tree(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                assertFalse(schema.path("additionalProperties").asBoolean()); assertTrue(schema.path("required").isArray());
            }
        }
    }
}
