package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {
    @Test void duplicateUrlAndHashAreNotReprocessed() {
        var store=new MemoryStore();var r=Fixtures.release("duplicate");assertTrue(store.collect(r));assertFalse(store.collect(r));
        assertFalse(store.collect(new Release(Normalize.hash("other"),r.sourceId(),r.organization(),"https://example.invalid/other",r.publishedAt(),r.collectedAt(),r.title(),r.body(),r.contentHash(),"other.json")));
    }
    @Test void pipelineHasSeparateVerificationAndIsIdempotent() {
        var store=new MemoryStore();var a=Fixtures.generated(store);assertEquals(Status.DRAFT,a.status());assertTrue(a.factCheckPassed());
        assertEquals(3,store.budget("2026-10-12").calls());assertEquals(1,store.budget("2026-10-12").articleIds().size());
        assertEquals(0,new Pipeline(store,new MockAi(),Fixtures.config(),Fixtures.CLOCK).run(false).drafts());assertEquals(3,store.budget("2026-10-12").calls());
    }
    @Test void meaningfulStoryQuotaNeverExceedsEight() {
        var store=new MemoryStore();for(int i=0;i<10;i++)store.collect(Fixtures.release("fixture-"+i));
        var result=new Pipeline(store,new MockAi(),Fixtures.config(),Fixtures.CLOCK).run(false);assertEquals(6,result.drafts());assertEquals(6,store.editorial().size());
    }
    @Test void realSourcesDoNotGenerateMockNews() {
        var store=new MemoryStore();var r=Fixtures.release("real");store.collect(new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt(),r.collectedAt(),"Real press release",r.body(),r.contentHash(),r.blobPath()));
        assertEquals(0,new Pipeline(store,new MockAi(),Fixtures.config(),Fixtures.CLOCK).run(false).drafts());assertTrue(store.editorial().isEmpty());
    }
    @Test void olderPendingDocumentsBecomeContextWithoutRankingOrWriting() {
        var store=new MemoryStore();var r=Fixtures.release("old-context");
        store.collect(new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt().minus(java.time.Duration.ofDays(30)),r.collectedAt(),r.title(),r.body(),r.contentHash(),r.blobPath()));
        var result=new Pipeline(store,new MockAi(),Fixtures.config(),Fixtures.CLOCK).run(false);
        assertEquals(0,result.drafts());assertEquals(0,store.budget("2026-10-12").calls());assertEquals("CONTEXT",store.statuses.get(r.id()));
        assertEquals(1,store.recent(Fixtures.CLOCK.instant().minus(java.time.Duration.ofDays(90)),100).size());
    }
    @Test void crashesResumeVerificationWithoutGeneratingTwice() {
        var store=new MemoryStore();store.collect(Fixtures.release("resume"));var mock=new MockAi();int[] generations={0};
        Ai failing=new Ai(){public Map<String,Analysis> analyze(List<Release> r){return mock.analyze(r);}public Draft generate(List<Release> r){generations[0]++;return mock.generate(r);}public Verification verify(Draft d,List<Release> r){throw new IllegalStateException("synthetic crash");}public String model(){return mock.model();}};
        new Pipeline(store,failing,Fixtures.config(),Fixtures.CLOCK).run(false);assertEquals("VERIFYING",store.editorial().getFirst().phase());
        assertEquals(1,new Pipeline(store,mock,Fixtures.config(),Fixtures.CLOCK).run(false).drafts());assertEquals(1,generations[0]);assertEquals(1,store.editorial().size());
    }
    @Test void dailyBudgetStopsAiBeforeCallingIt() {
        var store=new MemoryStore();store.collect(Fixtures.release("budget"));Config config=Config.from(Map.of("MAX_AI_CALLS_PER_DAY","1"));
        new Pipeline(store,new MockAi(),config,Fixtures.CLOCK).run(false);assertEquals(1,store.budget("2026-10-12").calls());assertEquals("GENERATING",store.editorial().getFirst().phase());
    }
    @Test void invalidAiDraftAndUnknownEvidenceFailValidation() {
        assertThrows(IllegalArgumentException.class,() -> Validation.draft(new Draft("x","bad","invalid","",List.of())));
        var r=Fixtures.release("grounding");var d=new MockAi().generate(List.of(r));
        var claims=new ArrayList<>(d.claims());claims.set(0,new Claim(d.title(),r.id(),"Invented evidence that does not exist."));
        assertFalse(Validation.grounded(new Draft(d.title(),d.perex(),d.category(),d.markdown(),claims),List.of(r),new Verification(true,false,List.of())).passed());
    }
    @Test void numbersAndUnmappedProseFailGrounding() {
        var r=Fixtures.release("numbers");var d=new MockAi().generate(List.of(r));String extra="Nepodložená suma je 999 eur.";
        var claims=new ArrayList<>(d.claims());claims.add(new Claim(extra,r.id(),r.body()));
        var invalid=new Draft(d.title(),d.perex(),d.category(),d.markdown()+"\n\n"+extra,claims);
        assertFalse(Validation.grounded(invalid,List.of(r),new Verification(true,false,List.of())).passed());
        assertFalse(Validation.grounded(new Draft(d.title(),d.perex(),d.category(),d.markdown()+"\n\n"+extra,d.claims()),List.of(r),new Verification(true,false,List.of())).passed());
    }
}
