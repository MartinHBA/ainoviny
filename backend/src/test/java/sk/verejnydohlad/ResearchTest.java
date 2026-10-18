package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchTest {
    @Test void archiveSupplementRequiresConcreteTopicInsteadOfGenericGovernmentFinanceBoilerplate(){
        var seed=document("mochovce-focus","mh-sr","Štvrtý blok Mochoviec dodal elektrinu do siete","Ministerstvo hospodárstva informovalo o verejných financiách a rozhodnutiach na Slovensku. Štvrtý blok Mochoviec sa pripojil do siete.");
        var budget=document("unrelated-budget","rrz-sk","RRZ hodnotí rozpočet verejnej správy","Ministerstvo hospodárstva informovalo o verejných financiách a rozhodnutiach na Slovensku. Rozpočet a verejný dlh sú predmetom hodnotenia.");
        var training=document("unrelated-training","nku-sr","Rekvalifikačné kurzy a ich prínos","Ministerstvo informovalo o rozhodnutiach, financovaní a verejných údajoch na Slovensku.");
        var reactor=document("reactor-regulator","ujd-sr","Bezpečnosť jadrových zariadení","Podklad opisuje štvrtý blok elektrárne Mochovce a podmienky jeho pripojenia do siete.");
        var concept=new Draft(seed.title(),"Podľa ministerstva ide o míľnik.","Slovensko","",List.of());
        assertEquals(List.of(seed,reactor),Research.targeted(concept,List.of(seed),List.of("Mochovce elektrina"),List.of(budget,training,reactor)));
        assertEquals(List.of(seed),Research.targeted(concept,List.of(seed),List.of("Mochovce elektrina"),List.of(budget,training)));
        var fiscal=document("budget-focus","mf-sr","Návrh štátneho rozpočtu","Predkladateľ uvádza návrh rozpočtu.");
        var debt=document("fiscal-assessment","rrz-sk","Deficit zostáva vysoký a dlh rastie","RRZ hodnotí predložený rozpočet a jeho deficit.");
        assertEquals(List.of(fiscal,debt),Research.targeted(new Draft(fiscal.title(),"","Slovensko","",List.of()),List.of(fiscal),List.of("rozpočet"),List.of(debt)));
    }
    static Release document(String seed,String institution,String title,String body) {
        var r=Fixtures.release(seed);
        return new Release(r.id(),institution,institution,r.originalUrl(),r.publishedAt(),r.collectedAt(),title,body,Normalize.hash(body),r.blobPath());
    }
    static ResearchBrief brief(List<Release> sources,boolean sensitive) {
        return new ResearchBrief(true,sensitive,"Čo ukazujú dva syntetické podklady?",
            sources.stream().map(r -> new GenerationClaim(r.body(),r.id(),"body-0")).toList(),List.of("Syntetické podklady."),List.of("Private unanswered question."));
    }
    @Test void retrievalUsesRelatedDocumentsFromOtherInstitutionsAndHasSixDocumentBound() {
        var seed=document("budget-seed","finance","Rozpočet a deficit školstva","Rozpočet školstva hodnotí deficit a financovanie škôl.");
        var unrelated=document("ceremony","other","Slovenská republika otvára výstavu","Ministerstvo informuje o verejnom otvorení výstavy.");
        var archive=new ArrayList<Release>();archive.add(unrelated);
        for(int i=0;i<12;i++)archive.add(document("related-"+i,"control","Deficit rozpočtu školstva","Financovanie školstva a deficit rozpočtu sú predmetom hodnotenia."));
        var found=Research.related(List.of(seed),archive);assertEquals(6,found.size());assertEquals(seed,found.getFirst());assertFalse(found.contains(unrelated));
        assertTrue(found.stream().skip(1).allMatch(r -> r.sourceId().equals("control")));
    }
    @Test void analyticalBriefCannotInventSourcesEvidenceNumbersOrTwoInstitutionSupport() {
        var a=Fixtures.release("analyst-a");var b=document("analyst-b","second","[SYNTHETIC] Second document",a.body()+" Second document.");
        var sources=List.of(a,b);Research.validate(brief(sources,false),sources);
        for(var claim:List.of(new GenerationClaim("Invented figure 999 euros.",a.id(),"body-0"),new GenerationClaim(a.body(),a.id(),"missing"),
            new GenerationClaim(a.body(),Normalize.hash("unknown"),"body-0"))) {
            var invalid=new ResearchBrief(true,false,"Test question",List.of(claim),List.of(),List.of());
            assertThrows(IllegalArgumentException.class,() -> Research.validate(invalid,sources));
        }
        assertThrows(IllegalArgumentException.class,() -> Research.validate(brief(List.of(a),false),List.of(a)));
        assertThrows(IllegalArgumentException.class,() -> Research.coverage(new MockAi().generate(List.of(a)),brief(sources,false),sources));
    }
    static class AnalystAi implements Ai {
        final MockAi mock=new MockAi();int researchCalls;boolean failGeneration;boolean sensitive;
        public Map<String,Analysis> analyze(List<Release> sources) {
            var map=new HashMap<String,Analysis>();sources.forEach(r -> map.put(r.id(),new Analysis(90,"shared-synthetic-topic","Slovensko","Synthetic comparison.")));return map;
        }
        public ResearchBrief research(List<Release> sources) { researchCalls++;return brief(sources,sensitive); }
        public Draft generate(List<Release> sources) { throw new AssertionError("Must pass research brief."); }
        public Draft generate(List<Release> sources,ResearchBrief brief) {
            if(failGeneration)throw new IllegalStateException("Synthetic generation interruption");assertNotNull(brief);
            var d=mock.generate(sources);var other=sources.get(1);var claims=new ArrayList<>(d.claims());claims.add(new Claim(other.body(),other.id(),other.body()));
            return new Draft(d.title(),d.perex(),d.category(),d.markdown()+"\n\n"+other.body(),claims);
        }
        public Verification verify(Draft d,List<Release> sources) { return new Verification(true,false,List.of()); }
        public EditorDecision review(Draft d,List<Release> sources) { return new EditorDecision(true,false,List.of(),"Synthetic approval."); }
        public String model() {return mock.model();}public String editorModel(){return mock.editorModel();}
    }
    static MemoryStore input() {
        var store=new MemoryStore();var a=Fixtures.release("analyst-first");store.collect(a);
        store.collect(document("analyst-second","second","[SYNTHETIC] Second document",a.body()+" Second distinct source document."));return store;
    }
    @Test void interruptedWritingReusesPersistedResearchWithoutAnotherPaidAnalystCall() {
        var store=input();var ai=new AnalystAi();ai.failGeneration=true;var pipeline=new Pipeline(store,ai,Fixtures.config(),Fixtures.CLOCK);
        assertEquals(0,pipeline.run(false).drafts());assertEquals(1,ai.researchCalls);
        assertTrue(store.blobs.keySet().stream().anyMatch(k -> k.startsWith("articles/research/")));
        ai.failGeneration=false;var result=pipeline.run(false);assertEquals(1,result.drafts());assertTrue(result.errors().isEmpty());assertEquals(1,ai.researchCalls);
        assertEquals(2,store.editorial().getFirst().sourceIds().size());assertEquals(5,store.budget("2026-10-12").calls());
    }
    @Test void analystSensitiveFlagSurvivesWriterVerifierAndPreventsAutomaticPublication() {
        var store=input();var ai=new AnalystAi();ai.sensitive=true;
        var config=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true"));
        var result=new Pipeline(store,ai,config,Fixtures.CLOCK).run(false);assertEquals(1,result.rejected());assertEquals(0,result.published());
        assertTrue(store.editorial().getFirst().sensitive());assertEquals("Automatic checks",store.editorial().getFirst().reviewer());
    }
    @Test void crashAfterWritingCheckpointRetainsSensitiveResearchAudit() {
        var store=new MemoryStore(){boolean failOnce=true;@Override public void updateArticle(Article article,String version) {
            if(failOnce && article.phase().equals("VERIFYING")){failOnce=false;throw new IllegalStateException("Checkpoint crash");}super.updateArticle(article,version);
        }};
        var a=Fixtures.release("checkpoint-analyst");store.collect(a);
        store.collect(document("checkpoint-other","other","[SYNTHETIC] Second document",a.body()+" Second document."));
        var ai=new AnalystAi();ai.sensitive=true;var config=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true"));
        var pipeline=new Pipeline(store,ai,config,Fixtures.CLOCK);pipeline.run(false);
        assertEquals(1,pipeline.run(false).rejected());assertTrue(store.editorial().getFirst().sensitive());assertEquals(1,ai.researchCalls);
    }
    @Test void invalidResearchAttemptIsPrivateAndCannotBeReusedAsAnApprovedBrief() {
        var store=input();var ai=new AnalystAi(){@Override public ResearchBrief research(List<Release> sources) {
            researchCalls++;if(researchCalls>1)return brief(sources,false);
            return new ResearchBrief(true,false,"Synthetic question",List.of(new GenerationClaim("Invalid source reference.",sources.getFirst().id(),"missing")),List.of(),List.of());
        }};
        var pipeline=new Pipeline(store,ai,Fixtures.config(),Fixtures.CLOCK);var failed=pipeline.run(false);
        assertEquals(0,failed.drafts());assertTrue(failed.errors().stream().anyMatch(e -> e.contains("Unknown research evidence identifier")));
        assertTrue(store.publicArticles.isEmpty());assertTrue(store.blobs.keySet().stream().anyMatch(k -> k.startsWith("articles/research-errors/")));
        assertFalse(store.blobs.keySet().stream().anyMatch(k -> k.startsWith("articles/research/")));
        assertEquals(1,pipeline.run(false).drafts());assertEquals(2,ai.researchCalls);
    }
    @Test void ungroundedNumericFindingNeverReachesWriterButSupportedMultiSourceComparisonSurvives() {
        var store=input();var ai=new AnalystAi(){@Override public ResearchBrief research(List<Release> sources) {
            researchCalls++;var original=brief(sources,true);var proposed=new ArrayList<>(original.findings());
            proposed.add(new GenerationClaim("Unsupported total of 999 euros.",sources.getFirst().id(),"body-0"));
            return new ResearchBrief(true,true,original.question(),proposed,original.limitations(),original.leads());
        }
        @Override public Draft generate(List<Release> sources,ResearchBrief brief) {
            assertTrue(brief.viable());assertTrue(brief.sensitive());assertEquals(2,brief.findings().size());
            assertFalse(brief.findings().stream().anyMatch(f -> f.text().contains("999")));return super.generate(sources,brief);
        }};
        var result=new Pipeline(store,ai,Fixtures.config(),Fixtures.CLOCK).run(false);assertEquals(1,result.drafts());assertTrue(result.errors().isEmpty());
        assertEquals(1,ai.researchCalls);assertTrue(store.editorial().getFirst().sensitive());
        assertTrue(store.blobs.keySet().stream().anyMatch(k -> k.startsWith("articles/research-errors/")));
    }
    @Test void droppingOnlyEvidenceForSecondInstitutionDowngradesToOrdinaryNews() {
        var sources=new ArrayList<>(input().releases.values());var original=brief(sources,false);
        var mixed=new ResearchBrief(true,false,original.question(),List.of(original.findings().getFirst(),
            new GenerationClaim("Unsupported total of 999 euros.",sources.get(1).id(),"body-0")),List.of(),List.of());
        var cleaned=Research.discardUnsupportedNumbers(mixed,sources);assertFalse(cleaned.viable());assertEquals(1,cleaned.findings().size());
        Research.validate(cleaned,sources);
    }
}
