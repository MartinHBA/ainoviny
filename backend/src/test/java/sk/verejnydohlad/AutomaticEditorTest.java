package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticEditorTest {
    static Config config() { return Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true")); }
    static class EditorAi implements Ai {
        final MockAi mock=new MockAi(); int reviews=0; boolean fail=false, sensitive=false, verified=true, failAnalysis=false;
        EditorDecision decision=new EditorDecision(true,false,List.of(),"This synthetic article is suitable for local publication.");
        public Map<String,Analysis> analyze(List<Release> r) { if(failAnalysis) throw new IllegalStateException("offline analysis failure"); return mock.analyze(r); }
        public Draft generate(List<Release> r) { return mock.generate(r); }
        public Verification verify(Draft d,List<Release> r) { return new Verification(verified,sensitive,List.of()); }
        public EditorDecision review(Draft d,List<Release> r) { reviews++; if(fail) throw new IllegalStateException("offline simulated refusal"); return decision; }
        public String model() { return mock.model(); }
        public String editorModel() { return "mock-offline-editor"; }
    }
    static Pipeline pipeline(MemoryStore store,EditorAi ai) { return new Pipeline(store,ai,config(),Fixtures.CLOCK); }
    static MemoryStore input() { var store=new MemoryStore();store.collect(Fixtures.release("automatic-editor"));return store; }

    @Test void strongerEditorApprovalPublishesOnceWithTruthfulDisclosureAndAudit() {
        var store=input();var ai=new EditorAi();var pipeline=pipeline(store,ai);var result=pipeline.run(false);
        assertEquals(1,result.published());assertEquals(0,result.rejected());assertTrue(result.errors().isEmpty());
        var a=store.editorial().getFirst();assertEquals(Status.PUBLISHED,a.status());assertEquals("AI editor: mock-offline-editor",a.reviewer());
        assertEquals(4,store.budget("2026-10-12").calls());assertEquals(1,ai.reviews);
        var detail=new PublicApi(store).detail(a.slug());assertNotNull(detail);assertTrue(detail.aiDisclosure().contains("neprešiel ľudskou"));
        assertEquals(1,store.blobs.keySet().stream().filter(k -> k.startsWith("articles/reviews/")).count());
        assertEquals(0,pipeline.run(false).published());assertEquals(1,ai.reviews);assertEquals(4,store.budget("2026-10-12").calls());
        assertEquals(1,new PublicApi(store).list(null,null,null).articles().size());
    }
    @Test void failedFactCheckAndSensitiveDraftAreRejectedWithoutAnotherPaidCall() {
        for(boolean sensitive:List.of(false,true)) {
            var store=input();var ai=new EditorAi();ai.sensitive=sensitive;ai.verified=sensitive;
            var result=pipeline(store,ai).run(false);assertEquals(1,result.rejected());assertEquals(0,result.published());
            assertEquals(Status.REJECTED,store.editorial().getFirst().status());assertEquals(0,ai.reviews);
            assertEquals("Automatic checks",store.editorial().getFirst().reviewer());
            assertTrue(new PublicApi(store).list(null,null,null).articles().isEmpty());
        }
    }
    @Test void chiefEditorRejectionIsTerminalAndRecordsItsReason() {
        var store=input();var ai=new EditorAi();ai.decision=new EditorDecision(false,false,List.of("Missing attribution."),"The title overstates the source evidence.");
        var pipeline=pipeline(store,ai);assertEquals(1,pipeline.run(false).rejected());
        var a=store.editorial().getFirst();assertTrue(a.issues().contains(ai.decision.reason()));assertEquals(Status.REJECTED,a.status());
        assertNull(new PublicApi(store).detail(a.slug()));pipeline.run(false);assertEquals(1,ai.reviews);
    }
    @Test void sensitiveChiefEditorDecisionCannotBePublished() {
        var store=input();var ai=new EditorAi();ai.decision=new EditorDecision(false,true,List.of("Damaging allegations."),"This sensitive article is excluded from automatic publication.");
        assertEquals(1,pipeline(store,ai).run(false).rejected());assertTrue(store.editorial().getFirst().sensitive());assertTrue(store.publicArticles.isEmpty());
    }
    @Test void refusalAndContradictoryApprovalLeaveDraftPrivate() {
        for(boolean refuse:List.of(false,true)) {
            var store=input();var ai=new EditorAi();ai.fail=refuse;
            ai.decision=new EditorDecision(true,false,List.of("An unresolved issue."),"This contradictory response must not approve anything.");
            var result=pipeline(store,ai).run(false);assertFalse(result.errors().isEmpty());assertEquals(0,result.published());
            assertEquals(Status.DRAFT,store.editorial().getFirst().status());assertTrue(store.publicArticles.isEmpty());
            assertEquals(4,store.budget("2026-10-12").calls());
        }
    }
    @Test void publicationFailureResumesFromAuditWithoutCallingEditorTwice() {
        var store=input();var ai=new EditorAi();store.failPublish=true;var pipeline=pipeline(store,ai);
        assertEquals(0,pipeline.run(false).published());assertEquals(Status.REVIEWED,store.editorial().getFirst().status());
        assertTrue(store.publicArticles.isEmpty());assertEquals(1,ai.reviews);
        store.failPublish=false;assertEquals(1,pipeline.run(false).published());assertEquals(1,ai.reviews);
        assertEquals(4,store.budget("2026-10-12").calls());
    }
    @Test void emptyApprovalReasonIsAuditedAndPublicationRetryDoesNotSpendAnotherCall() {
        var store=input();var ai=new EditorAi();ai.decision=new EditorDecision(true,false,List.of(),"");
        store.failPublish=true;var pipeline=pipeline(store,ai);
        assertEquals(0,pipeline.run(false).published());assertEquals(Status.REVIEWED,store.editorial().getFirst().status());
        var audit=store.blobs.entrySet().stream().filter(e -> e.getKey().startsWith("articles/reviews/")).findFirst().orElseThrow();
        assertEquals("",Json.read(audit.getValue(),AutomaticEditor.Audit.class).decision().reason());
        store.failPublish=false;assertEquals(1,pipeline.run(false).published());assertEquals(1,ai.reviews);
        assertEquals(4,store.budget("2026-10-12").calls());
    }
    @Test void crashAfterAuditBeforeApprovalAlsoResumesWithoutAnotherAiCall() {
        var store=new MemoryStore(){boolean failOnce=true;@Override public void updateArticle(Article a,String version){
            if(failOnce && a.status()==Status.REVIEWED){failOnce=false;throw new IllegalStateException("simulated crash");}super.updateArticle(a,version);
        }};
        store.collect(Fixtures.release("audit-crash"));var ai=new EditorAi();var pipeline=pipeline(store,ai);
        assertEquals(0,pipeline.run(false).published());assertEquals(Status.DRAFT,store.editorial().getFirst().status());
        assertEquals(1,pipeline.run(false).published());assertEquals(1,ai.reviews);
    }
    @Test void changedContentNeedsFreshChiefEditorDecision() {
        var store=input();var ai=new EditorAi();store.failPublish=true;var pipeline=pipeline(store,ai);pipeline.run(false);
        var a=store.editorial().getFirst();String title=a.title()+" po úprave";var claims=new ArrayList<>(a.claims());
        claims.set(0,new Claim(title,claims.getFirst().sourceId(),claims.getFirst().evidence()));
        var editor=new Editorial(store,Fixtures.CLOCK);editor.edit(a.id(),new Draft(title,a.perex(),a.category(),store.blob("articles",a.blobPath()),claims));
        editor.recheck(a.id(),ai,config());store.failPublish=false;
        assertEquals(1,pipeline.run(false).published());assertEquals(2,ai.reviews);assertEquals(title,new PublicApi(store).detail(a.slug()).headline());
    }
    @Test void changedSourceInvalidatesPreviouslyApprovedPublication() {
        var store=input();var ai=new EditorAi();store.failPublish=true;var pipeline=pipeline(store,ai);pipeline.run(false);
        var r=store.releases.values().iterator().next();String body=r.body()+" Source changed.";
        store.releases.put(r.id(),new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt(),r.collectedAt(),r.title(),body,Normalize.hash(body),r.blobPath()));
        store.failPublish=false;var result=pipeline.run(false);assertEquals(0,result.published());assertFalse(result.errors().isEmpty());
        assertTrue(store.publicArticles.isEmpty());assertEquals(1,ai.reviews);
    }
    @Test void dailyBudgetExhaustionWaitsForNextDayAndDoesNotBypassEditor() {
        var store=input();var ai=new EditorAi();var config=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true","MAX_AI_CALLS_PER_DAY","3"));
        assertEquals(0,new Pipeline(store,ai,config,Fixtures.CLOCK).run(false).published());assertEquals(0,ai.reviews);assertTrue(store.publicArticles.isEmpty());
        var tomorrow=Clock.offset(Fixtures.CLOCK,Duration.ofDays(1));assertEquals(1,new Pipeline(store,ai,config,tomorrow).run(false).published());
        assertEquals(1,store.budget("2026-10-13").calls());
    }
    @Test void manualModeKeepsDraftsAndInvalidAutomaticConfigurationFailsEarly() {
        var store=input();var ai=new EditorAi();new Pipeline(store,ai,Fixtures.config(),Fixtures.CLOCK).run(false);
        assertEquals(Status.DRAFT,store.editorial().getFirst().status());assertEquals(0,ai.reviews);
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("AUTO_PUBLISH_ENABLED","yes")));
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("AUTO_PUBLISH_ENABLED","true")));
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("AI_MODE","azure","AZURE_OPENAI_ENDPOINT","https://test.openai.azure.com","AUTO_PUBLISH_ENABLED","true","AZURE_OPENAI_EDITOR_DEPLOYMENT","gpt-4.1-mini")));
        assertThrows(IllegalArgumentException.class,() -> Validation.editorDecision(Json.read("{\"approved\":true,\"sensitive\":false,\"issues\":null,\"reason\":\"Invalid null issues.\"}",EditorDecision.class)));
        assertThrows(IllegalArgumentException.class,() -> Json.read("{\"approved\":true,\"sensitive\":false,\"reason\":\"Missing required issues.\"}",EditorDecision.class));
    }
    @Test void unrelatedRankingFailureDoesNotStrandReviewedPublication() {
        var store=input();var ai=new EditorAi();store.failPublish=true;var pipeline=pipeline(store,ai);pipeline.run(false);
        store.collect(Fixtures.release("unrelated-new-source"));ai.failAnalysis=true;store.failPublish=false;
        var result=pipeline.run(false);assertEquals(1,result.published());assertFalse(result.errors().isEmpty());assertEquals(1,ai.reviews);
    }
    @Test void eightConceptsHaveSeparateFivePublicationLimitAndResumeNextDay() {
        var store=new MemoryStore();for(int i=0;i<10;i++)store.collect(Fixtures.release("auto-quota-"+i));var ai=new EditorAi();
        var pipeline=pipeline(store,ai);var result=pipeline.run(false);assertEquals(8,result.drafts());assertEquals(5,result.published());assertEquals(5,ai.reviews);
        assertEquals(0,pipeline.run(false).published());assertEquals(5,store.publicArticles.size());assertEquals(8,store.budget("2026-10-12").articleIds().size());
        assertEquals(5,1+store.latest().articles().size());
        new Pipeline(store,ai,config(),Clock.offset(Fixtures.CLOCK,Duration.ofDays(1))).run(false);
        assertEquals(5,store.publicArticles.values().stream().filter(a -> a.editionDate().equals("2026-10-13")).count());
        assertEquals(5,store.publicArticles.values().stream().filter(a -> a.editionDate().equals("2026-10-12")).count());
    }
    @Test void rejectedConceptsLeaveFivePublicationSlotsAvailable() {
        var store=new MemoryStore();for(int i=0;i<8;i++)store.collect(Fixtures.release("rejection-slots-"+i));
        var ai=new EditorAi(){@Override public EditorDecision review(Draft d,List<Release> sources) {
            reviews++;return reviews<=3?new EditorDecision(false,false,List.of("Missing attribution."),"Unsupported conclusion."):decision;
        }};
        var result=pipeline(store,ai).run(false);assertEquals(8,result.drafts());assertEquals(3,result.rejected());assertEquals(5,result.published());
        assertEquals(8,store.budget("2026-10-12").articleIds().size());assertTrue(result.errors().isEmpty());
    }
}
