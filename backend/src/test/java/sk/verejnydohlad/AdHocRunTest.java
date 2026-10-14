package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class AdHocRunTest {
    static Config config(){return Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true"));}
    static Pipeline pipeline(MemoryStore store){return new Pipeline(store,new MockAi(),config(),Fixtures.CLOCK);}
    static void inputs(MemoryStore store,String prefix,int count){for(int i=0;i<count;i++)store.collect(Fixtures.release(prefix+"-"+i));}
    @Test void exhaustedDayDoesNotBlockManualCallsConceptsOrPublicationAndItsLedgerStaysUnchanged() {
        var store=new MemoryStore();inputs(store,"scheduled",3);assertEquals(3,pipeline(store).run(false).published());
        var old=store.budget("2026-10-12");var full=new Budget(40,List.of("one","two","three","four","five","six"));store.budgets.put("2026-10-12",full);
        inputs(store,"manual-new",1);var scope=RunScope.adHoc();assertEquals(1,pipeline(store).run(false,scope).published());
        assertEquals(full,store.budget("2026-10-12"));assertEquals(4,store.budget(scope.budgetKey(Fixtures.CLOCK)).calls());
        assertEquals(1,store.publicationScopes.values().stream().filter(scope.label()::equals).count());assertTrue(old.calls()>0);
        assertEquals(0,pipeline(store).run(false).published());assertEquals(full,store.budget("2026-10-12"));
    }
    @Test void manualPublicationsDoNotConsumeNextScheduledPublicationAllowance() {
        var store=new MemoryStore();inputs(store,"manual-first",3);var scope=RunScope.adHoc();
        assertEquals(3,pipeline(store).run(false,scope).published());assertEquals(new Budget(0,List.of()),store.budget("2026-10-12"));
        assertFalse(new Editorial(store,Fixtures.CLOCK,3).publicationLimitReached());
        var manualBudget=store.budget(scope.label());inputs(store,"scheduled-next",3);assertEquals(3,pipeline(store).run(false).published());
        assertEquals(6,store.publicArticles.size());assertEquals(manualBudget,store.budget(scope.label()));
        assertEquals(3,store.publicationScopes.values().stream().filter("scheduled"::equals).count());
    }
    @Test void sameManualRunIdPreservesItsSixConceptThreePublicationCapsAndDeduplicatesRetries() {
        var store=new MemoryStore();inputs(store,"bounded-manual",9);var scope=RunScope.adHoc();var p=pipeline(store);
        var first=p.run(false,scope);assertEquals(6,first.drafts());assertEquals(3,first.published());
        var budget=store.budget(scope.label());assertEquals(6,budget.articleIds().size());assertTrue(budget.calls()<=40);
        assertEquals(0,p.run(false,scope).published());assertEquals(budget,store.budget(scope.label()));assertEquals(3,store.publicArticles.size());
        assertEquals(new Budget(0,List.of()),store.budget("2026-10-12"));assertTrue(store.runScopes.stream().allMatch(scope.label()::equals));
    }
    @Test void failedPublicationHasNoManualScopeUntilAtomicCommitAndRetryReusesApproval() {
        var store=new MemoryStore();inputs(store,"manual-retry",1);store.failPublish=true;var scope=RunScope.adHoc();var p=pipeline(store);
        assertEquals(0,p.run(false,scope).published());var budget=store.budget(scope.label());assertTrue(store.publicationScopes.isEmpty());
        store.failPublish=false;assertEquals(1,p.run(false,scope).published());assertEquals(budget,store.budget(scope.label()));
        assertEquals(scope.label(),store.publicationScope(store.editorial().getFirst().id()));assertEquals(new Budget(0,List.of()),store.budget("2026-10-12"));
    }
    @Test void independentManualBudgetStillStopsAtConfiguredCallCapAndDoesNotResetOnRetry() {
        var store=new MemoryStore();inputs(store,"manual-call-cap",1);var scope=RunScope.adHoc();
        var cfg=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true","MAX_AI_CALLS_PER_DAY","2"));
        var p=new Pipeline(store,new MockAi(),cfg,Fixtures.CLOCK);p.run(false,scope);p.run(false,scope);
        assertEquals(2,store.budget(scope.label()).calls());assertTrue(store.publicArticles.isEmpty());assertEquals(new Budget(0,List.of()),store.budget("2026-10-12"));
    }
    @Test void manualRunCannotBypassGlobalLeaseOrSensitiveChecks() {
        var store=new MemoryStore();inputs(store,"manual-lock",1);var scope=RunScope.adHoc();
        try(var guard=store.lock()){assertFalse(pipeline(store).run(false,scope).errors().isEmpty());assertEquals(0,store.budget(scope.label()).calls());}
        var ai=new CitationCorrectionTest.CorrectingAi(){@Override public Verification verify(Draft draft,List<Release> sources){return new Verification(true,true,List.of());}};
        assertEquals(1,new Pipeline(store,ai,config(),Fixtures.CLOCK).run(false,scope).rejected());assertTrue(store.publicArticles.isEmpty());
    }
    @Test void explicitNativeAdminMarkerIsManualAndInvalidManualPayloadCannotBecomeScheduled() {
        String id=UUID.randomUUID().toString();assertEquals(new RunScope(id),RunScope.manualInput("adhoc:"+id));assertTrue(RunScope.manualInput("adhoc").manual());
        assertTrue(RunScope.manualInput("manual-after-update").manual());
        assertEquals(new RunScope(id),RunScope.manualInput(Json.write("adhoc:"+id)));
        assertEquals(new RunScope(id),RunScope.manualInput(Json.write(Map.of("input","adhoc:"+id))));
        for(String invalid:List.of("","scheduled","adhoc:not-a-uuid","{\"ScheduleStatus\":{},\"IsPastDue\":false,\"note\":\"adhoc\"}"))
            assertThrows(IllegalArgumentException.class,() -> RunScope.manualInput(invalid));
        assertThrows(IllegalArgumentException.class,() -> RunScope.manualInput(null));
    }
    @Test void researchedManualFlowRoutesEveryStageIncludingFinalEditorToItsOwnLedger() {
        var store=ResearchFlowTest.store();var ai=new ResearchFlowTest.AssistantAi();var scope=RunScope.adHoc();
        var p=new Pipeline(store,ai,ResearchFlowTest.config(),Fixtures.CLOCK,ResearchFlowTest.lookup(ai));
        assertEquals(1,p.run(false,scope).published());assertEquals(7,store.budget(scope.label()).calls());
        assertEquals(new Budget(0,List.of()),store.budget("2026-10-12"));assertEquals(scope.label(),store.publicationScope(store.editorial().getFirst().id()));
    }
}
