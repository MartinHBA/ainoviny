package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchFlowTest {
    static Config config(String... overrides) {
        var env=new HashMap<String,String>();env.put("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true");env.put("AUTO_PUBLISH_ENABLED","true");env.put("RESEARCH_FLOW_ENABLED","true");
        for(int i=0;i<overrides.length;i+=2)env.put(overrides[i],overrides[i+1]);return Config.from(env);
    }
    static Release source(String seed,String institution) {return ResearchTest.document(seed,institution,"[SYNTHETIC] Rozpočet verejnej správy",Fixtures.release(seed).body());}
    static MemoryStore store(){var store=new MemoryStore();store.collect(source("primary-budget","mf-sr"));return store;}
    static ResearchLookup lookup(AssistantAi ai) {
        return (plan,concept,seeds,store,guard,deadline) -> {
            ai.events.add("lookup");ai.lookups++;assertTrue(plan.sourceIds().contains("rrz-sk"));
            var second=source("independent-assessment","rrz-sk");guard.check();if(store.collect(second))store.releaseStatus(second,"CONTEXT",null);
            return new ResearchLookup.Result(List.of(seeds.getFirst(),second),List.of("Syntetická skúška obmedzenej rešerše."));
        };
    }
    static class AssistantAi implements Ai {
        final MockAi mock=new MockAi();
        public Map<String,Analysis> analyze(List<Release> sources){return mock.analyze(sources);}
        public String model(){return mock.model();}public String editorModel(){return mock.editorModel();}
        final List<String> events=new ArrayList<>();int initial,plans,briefs,rewrites,lookups,verifications,reviews;boolean failRewrite,sensitive;
        @Override public Draft generate(List<Release> sources){events.add("initial");initial++;return mock.generate(sources);}
        @Override public ResearchPlan plan(Draft initial,List<Release> sources) {
            events.add("plan");plans++;return new ResearchPlan("Čo ukazuje nezávislé hodnotenie rozpočtu?",List.of("rozpočet"),List.of("rrz-sk","ifp-sr"));
        }
        @Override public ResearchBrief research(Draft initial,List<Release> sources,List<String> gaps) {
            events.add("brief");briefs++;assertFalse(gaps.isEmpty());return ResearchTest.brief(sources,sensitive);
        }
        @Override public Draft rewrite(Draft initial,List<Release> sources,ResearchBrief brief) {
            events.add("rewrite");rewrites++;if(failRewrite)throw new IllegalStateException("Synthetic rewrite interruption");
            var second=sources.get(1);var claims=new ArrayList<>(initial.claims());claims.add(new Claim(second.body(),second.id(),second.body()));
            return new Draft(initial.title(),initial.perex(),initial.category(),initial.markdown()+"\n\n"+second.body(),claims);
        }
        @Override public Verification verify(Draft draft,List<Release> sources){events.add("verify");verifications++;assertEquals(2,sources.size());assertTrue(draft.markdown().contains("independent-assessment"));return mock.verify(draft,sources);}
        @Override public EditorDecision review(Draft draft,List<Release> sources){events.add("editor");reviews++;return mock.review(draft,sources);}
    }
    @Test void firstConceptIsResearchedRewrittenAndOnlyThenVerifiedAndPublished() {
        var store=store();var ai=new AssistantAi();var result=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai)).run(false);
        assertEquals(1,result.published());assertTrue(result.errors().isEmpty(),result.errors().toString());
        assertEquals(List.of("initial","plan","lookup","brief","rewrite","verify","editor"),ai.events);
        var article=store.editorial().getFirst();assertEquals(2,article.sourceIds().size());assertEquals(2,article.sourceUrls().size());
        assertEquals("CONTEXT",store.releaseStatus(store.release(article.sourceIds().get(1))));
        assertEquals(7,store.budget("2026-10-12").calls());assertEquals(1,store.budget("2026-10-12").articleIds().size());
        assertTrue(store.blobs.keySet().stream().anyMatch(p -> p.endsWith("/retrieval.json")));
        assertEquals(0,new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai)).run(false).published());assertEquals(7,store.budget("2026-10-12").calls());
    }
    @Test void interruptedRewriteReusesInitialPlanRetrievalAndBriefWithoutRepeatingPaidResearch() {
        var store=store();var ai=new AssistantAi();ai.failRewrite=true;var pipeline=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai));
        assertEquals(0,pipeline.run(false).published());assertEquals("DRAFTING",store.editorial().getFirst().phase());
        assertTrue(store.publicArticles.isEmpty());ai.failRewrite=false;assertEquals(1,pipeline.run(false).published());
        assertEquals(1,ai.initial);assertEquals(1,ai.plans);assertEquals(1,ai.lookups);assertEquals(1,ai.briefs);assertEquals(2,ai.rewrites);
        assertEquals(8,store.budget("2026-10-12").calls());
    }
    @Test void sensitiveBriefSurvivesCrashAfterRewriteAndCannotReachFinalAutomaticEditor() {
        var store=new MemoryStore(){boolean once=true;@Override public void updateArticle(Article a,String version){
            if(once && a.phase().equals("VERIFYING")){once=false;throw new IllegalStateException("Synthetic checkpoint crash");}super.updateArticle(a,version);
        }};store.collect(source("primary-budget","mf-sr"));var ai=new AssistantAi();ai.sensitive=true;
        var pipeline=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai));pipeline.run(false);
        assertEquals(1,pipeline.run(false).rejected());assertEquals(1,ai.rewrites);assertEquals(1,ai.briefs);assertEquals(0,ai.reviews);assertTrue(store.publicArticles.isEmpty());
    }
    @Test void budgetExhaustionPreservesPrivateSnapshotWithoutCallingAnalystOrWriterAgain() {
        var store=store();var ai=new AssistantAi();var pipeline=new Pipeline(store,ai,config("MAX_AI_CALLS_PER_DAY","3"),Fixtures.CLOCK,lookup(ai));
        pipeline.run(false);pipeline.run(false);assertEquals(3,store.budget("2026-10-12").calls());
        assertEquals(1,ai.initial);assertEquals(1,ai.plans);assertEquals(1,ai.lookups);assertEquals(0,ai.briefs);assertEquals(0,ai.rewrites);assertTrue(store.publicArticles.isEmpty());
    }
    @Test void unknownModelSelectedSourceNeverInvokesRetrievalOrPublication() {
        var store=store();var ai=new AssistantAi(){@Override public ResearchPlan plan(Draft initial,List<Release> seeds) {
            return new ResearchPlan("Invalid private plan",List.of("rozpočet"),List.of("https://newspaper.invalid"));
        }};
        var result=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai)).run(false);assertEquals(0,result.published());assertEquals(0,ai.lookups);assertFalse(result.errors().isEmpty());
    }
    @Test void budgetMentionLateInPerexStillForcesRRZAttemptWhenPlannerOmitsIt() {
        var store=store();var ai=new AssistantAi(){
            @Override public Draft generate(List<Release> sources) {
                var d=super.generate(sources);String perex="Podľa syntetického podkladu je predmetom rokovania nový plán verejných výdavkov a príjmov, konkrétne štátny rozpočet.";
                var claims=new ArrayList<>(d.claims());claims.set(1,new Claim(perex,sources.getFirst().id(),sources.getFirst().body()));
                return new Draft(d.title(),perex,d.category(),d.markdown(),claims);
            }
            @Override public ResearchPlan plan(Draft initial,List<Release> seeds){return new ResearchPlan("Aké sú predpoklady?",List.of("rozpočet"),List.of("ifp-sr"));}
        };
        var result=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai)).run(false);assertEquals(1,result.published());assertEquals(1,ai.lookups);
    }
    @Test void completedArticleGetsEditorBudgetBeforeLaterConceptCanExhaustDailyCalls() {
        var store=store();store.collect(source("second-topic","mf-sr"));var ai=new AssistantAi();
        var result=new Pipeline(store,ai,config("MAX_AI_CALLS_PER_DAY","10"),Fixtures.CLOCK,lookup(ai)).run(false);
        assertEquals(1,result.published());assertEquals(1,ai.reviews);assertEquals(10,store.budget("2026-10-12").calls());assertEquals(1,store.publicArticles.size());
    }
    @Test void writerCannotDropRRZPerspectiveWhileCitingAnotherIndependentInstitution() {
        var store=store();var ai=new AssistantAi(){@Override public Draft rewrite(Draft initial,List<Release> sources,ResearchBrief brief) {
            var other=sources.get(2);var claims=new ArrayList<>(initial.claims());claims.add(new Claim(other.body(),other.id(),other.body()));
            return new Draft(initial.title(),initial.perex(),initial.category(),initial.markdown()+"\n\n"+other.body(),claims);
        }};
        ResearchLookup lookup=(plan,concept,seeds,data,guard,deadline) -> {
            var rrz=source("mandatory-assessment","rrz-sk");var nbs=source("economic-context","nbs-sk");
            for(var r:List.of(rrz,nbs)){data.collect(r);data.releaseStatus(r,"CONTEXT",null);}
            return new ResearchLookup.Result(List.of(seeds.getFirst(),rrz,nbs),List.of("Synthetic retrieval caveat."));
        };
        var result=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup).run(false);assertEquals(0,result.published());assertEquals(0,ai.verifications);assertEquals(0,ai.reviews);
        assertFalse(result.errors().isEmpty());assertTrue(store.publicArticles.isEmpty());
    }
    @Test void sixResearchedConceptsKeepSeparateThreePublicationCeiling() {
        var store=new MemoryStore();for(int i=0;i<7;i++)store.collect(source("researched-quota-"+i,"mf-sr"));var ai=new AssistantAi();
        var result=new Pipeline(store,ai,config(),Fixtures.CLOCK,lookup(ai)).run(false);
        assertEquals(6,result.drafts());assertEquals(3,result.published());assertEquals(3,ai.reviews);assertEquals(6,store.budget("2026-10-12").articleIds().size());
        assertTrue(store.budget("2026-10-12").calls()<=40);assertEquals(3,store.editorial().stream().filter(a -> a.status()==Status.DRAFT).count());
    }
    @Test void separateMFIFPAndUHPSitesDoNotQualifyAsIndependentInstitutionCoverage() {
        var sources=List.of(source("finance","mf-sr"),source("institute","ifp-sr"),source("value","uhp-sr"));
        assertFalse(Research.multiSource(sources));assertThrows(IllegalArgumentException.class,() -> Research.validate(ResearchTest.brief(sources,false),sources));
    }
    @Test void completeCatalogHasUniqueIdsAndAzureDefaultsEnableFlowWhileMocksStayOffline() {
        assertEquals(30,ResearchRegistry.SITES.size());assertEquals(30,ResearchRegistry.SITES.stream().map(ResearchRegistry.Site::id).distinct().count());
        assertFalse(Fixtures.config().researchFlow());assertTrue(AzureAiTest.config().researchFlow());
        assertThrows(IllegalArgumentException.class,() -> Config.from(Map.of("RESEARCH_FLOW_ENABLED","perhaps")));
        var old=Config.from(Map.of("MAX_ARTICLES_PER_DAY","8","MAX_PUBLISHED_ARTICLES_PER_DAY","5"));assertEquals(6,old.maxArticles());assertEquals(3,old.maxPublished());
    }
}
