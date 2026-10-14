package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class CitationCorrectionTest {
    @Test void ordinaryCorrectionDoesNotMistakeMFAndIFPForAnIndependentAnalyticalComparison() {
        var store=new MemoryStore();var parent=rejected(store);var first=store.release(parent.sourceIds().getFirst());
        var mf=new Release(first.id(),"mf-sr",first.organization(),first.originalUrl(),first.publishedAt(),first.collectedAt(),first.title(),first.body(),first.contentHash(),first.blobPath());
        store.releases.put(mf.id(),mf);
        var ifp=ResearchFlowTest.source("same-ministry-context","ifp-sr");store.collect(ifp);store.releaseStatus(ifp,"CONTEXT",null);
        var claims=new ArrayList<>(parent.claims());claims.add(new Claim(ifp.body(),ifp.id(),ifp.body()));
        String markdown=store.blob("articles",parent.blobPath())+"\n\n"+ifp.body(),hash=Normalize.hash(markdown),path="drafts/"+parent.id()+"/"+hash+".md";
        store.putBlob("articles",path,markdown);
        var withContext=new Article(parent.id(),parent.slug(),parent.title(),parent.perex(),parent.category(),parent.createdAt(),parent.generatedAt(),null,Status.REJECTED,path,
            List.of(mf.id(),ifp.id()),List.of(mf.originalUrl(),ifp.originalUrl()),parent.aiModel(),parent.reviewedAt(),parent.reviewer(),null,hash,false,false,parent.issues(),claims,"COMPLETE",0,parent.editionDate());
        store.updateArticle(withContext,store.article(parent.id()).version());
        var result=new Pipeline(store,new CorrectingAi(),config(),Fixtures.CLOCK).run(false);assertEquals(1,result.published());assertTrue(result.errors().isEmpty());
        assertEquals(withContext,store.article(parent.id()).value());
    }
    static Config config() {return Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true"));}
    static class CorrectingAi implements Ai {
        final MockAi mock=new MockAi();int corrections;boolean badCorrection;
        public Map<String,Analysis> analyze(List<Release> sources){return mock.analyze(sources);}
        public Draft generate(List<Release> sources){return mock.generate(sources);}
        public ResearchBrief research(List<Release> sources){return new ResearchBrief(false,false,"",List.of(),List.of("No useful comparison."),List.of());}
        public Verification verify(Draft draft,List<Release> sources){return mock.verify(draft,sources);}
        public Draft correct(Draft old,List<Release> sources,List<String> issues){corrections++;return badCorrection?old:mock.generate(sources);}
        public EditorDecision review(Draft draft,List<Release> sources){return mock.review(draft,sources);}
        public String model(){return mock.model();}public String editorModel(){return mock.editorModel();}
    }
    static Article rejected(MemoryStore store) {
        var source=Fixtures.release("citation-parent");store.collect(source);var d=new MockAi().generate(List.of(source));
        String extra="Nepodložená suma je 999 eur.";var claims=new ArrayList<>(d.claims());claims.add(new Claim(extra,source.id(),source.body()));
        var bad=new Draft(d.title(),d.perex(),d.category(),d.markdown()+"\n\n"+extra,claims);
        String id=SourceInputs.topicId(List.of(source.id())),hash=Normalize.hash(bad.markdown()),path="drafts/"+id+"/"+hash+".md";
        var article=new Article(id,"rejected-"+id.substring(0,16),bad.title(),bad.perex(),bad.category(),Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),null,Status.REJECTED,
            path,List.of(source.id()),List.of(source.originalUrl()),"mock-offline-synthetic",Fixtures.CLOCK.instant(),"Automatic checks",null,hash,false,false,
            List.of("Numeric claim is not grounded: 999"),bad.claims(),"COMPLETE",0,"2026-10-12");
        store.putBlob("articles",path,bad.markdown());store.createArticle(article);store.releaseStatus(source,"DONE",null);return article;
    }
    @Test void rejectedParentRemainsImmutableWhileCorrectionPassesFreshChecksAndPublishesOnce() {
        var store=new MemoryStore();var parent=rejected(store);var ai=new CorrectingAi();var pipeline=new Pipeline(store,ai,config(),Fixtures.CLOCK);
        var result=pipeline.run(false);assertEquals(1,result.drafts());assertEquals(1,result.published());assertTrue(result.errors().isEmpty());
        assertEquals(parent,store.article(parent.id()).value());assertEquals(Status.PUBLISHED,store.article(SourceInputs.correctionId(parent.id())).value().status());
        assertEquals(3,store.budget("2026-10-12").calls());assertEquals(1,ai.corrections);
        assertEquals(0,pipeline.run(false).published());assertEquals(1,ai.corrections);assertEquals(2,store.articles.size());
    }
    @Test void unsupportedCorrectionCannotPublishOrCreateAnUnboundedCorrectionChain() {
        var store=new MemoryStore();var parent=rejected(store);var ai=new CorrectingAi();ai.badCorrection=true;var pipeline=new Pipeline(store,ai,config(),Fixtures.CLOCK);
        assertEquals(1,pipeline.run(false).rejected());assertTrue(store.publicArticles.isEmpty());
        assertEquals(Status.REJECTED,store.article(SourceInputs.correctionId(parent.id())).value().status());
        pipeline.run(false);assertEquals(2,store.articles.size());assertEquals(1,ai.corrections);assertEquals(parent,store.article(parent.id()).value());
    }
    @Test void genuineFinalEditorManualAndSensitiveRejectionsAreNeverCorrected() {
        for(String reviewer:List.of("AI editor: mock-offline-editor","Human reviewer","Automatic checks")) {
            var store=new MemoryStore();var parent=rejected(store);parent=parent.state(Status.REJECTED,parent.reviewedAt(),reviewer,null,null,parent.blobPath());
            if(reviewer.equals("Automatic checks"))parent=parent.revise(new Draft(parent.title(),parent.perex(),parent.category(),store.blob("articles",parent.blobPath()),parent.claims()),parent.blobPath(),parent.contentHash(),
                new Verification(false,true,parent.issues()),Fixtures.CLOCK.instant()).state(Status.REJECTED,parent.reviewedAt(),reviewer,null,null,parent.blobPath());
            store.updateArticle(parent,store.article(parent.id()).version());var ai=new CorrectingAi();new Pipeline(store,ai,config(),Fixtures.CLOCK).run(false);
            assertEquals(0,ai.corrections);assertEquals(1,store.articles.size());assertTrue(store.publicArticles.isEmpty());
        }
    }
    @Test void writingCheckpointSurvivesCrashWithoutAnotherPaidCorrectionCall() {
        var store=new MemoryStore(){boolean failOnce=true;@Override public void updateArticle(Article a,String version){
            if(failOnce && a.phase().equals("VERIFYING")){failOnce=false;throw new IllegalStateException("Checkpoint crash");}super.updateArticle(a,version);
        }};
        var parent=rejected(store);var ai=new CorrectingAi();var pipeline=new Pipeline(store,ai,config(),Fixtures.CLOCK);
        assertEquals(0,pipeline.run(false).published());assertEquals("CORRECTING",store.article(SourceInputs.correctionId(parent.id())).value().phase());
        assertEquals(1,pipeline.run(false).published());assertEquals(1,ai.corrections);assertEquals(3,store.budget("2026-10-12").calls());
    }
    @Test void noAiBudgetKeepsCorrectionPrivateAndDoesNotResetReservations() {
        var store=new MemoryStore();var parent=rejected(store);var ai=new CorrectingAi();
        var limited=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true","MAX_AI_CALLS_PER_DAY","0"));
        new Pipeline(store,ai,limited,Fixtures.CLOCK).run(false);assertEquals(0,ai.corrections);assertTrue(store.publicArticles.isEmpty());
        assertEquals(1,store.budget("2026-10-12").articleIds().size());assertEquals("CORRECTING",store.article(SourceInputs.correctionId(parent.id())).value().phase());
        assertEquals(1,new Pipeline(store,ai,config(),Fixtures.CLOCK).run(false).published());assertEquals(1,ai.corrections);
    }
    @Test void sharedContextDoesNotConsumeOrSuppressAnotherNewsTopic() {
        var store=new MemoryStore();var a=Fixtures.release("context-topic-a");var original=Fixtures.release("context-topic-b");
        var b=new Release(original.id(),"other-institution",original.organization(),original.originalUrl(),original.publishedAt(),original.collectedAt(),original.title(),original.body(),original.contentHash(),original.blobPath());
        store.collect(a);store.collect(b);var ai=new CorrectingAi();
        var result=new Pipeline(store,ai,Fixtures.config(),Fixtures.CLOCK).run(false);
        assertEquals(2,result.drafts());assertTrue(result.errors().isEmpty());
        assertTrue(store.editorial().stream().allMatch(article -> SourceInputs.seeds(article).size()==1));
    }
    @Test void legacyContextMarkedDoneIsRestoredButActualPrimaryTopicsStayConsumed() {
        var store=new MemoryStore();var parent=rejected(store);var context=Fixtures.release("legacy-context");store.collect(context);
        store.releaseStatus(context,"DONE",new Analysis(0,"context-only","Slovensko","Synthetic low priority."));
        var withContext=new Article(parent.id(),parent.slug(),parent.title(),parent.perex(),parent.category(),parent.createdAt(),parent.generatedAt(),parent.publishedAt(),parent.status(),parent.blobPath(),
            List.of(parent.sourceIds().getFirst(),context.id()),List.of(parent.sourceUrls().getFirst(),context.originalUrl()),parent.aiModel(),parent.reviewedAt(),parent.reviewer(),parent.approvedHash(),parent.contentHash(),parent.factCheckPassed(),parent.sensitive(),parent.issues(),parent.claims(),parent.phase(),parent.editorialOrder(),parent.editionDate());
        store.updateArticle(withContext,store.article(parent.id()).version());new Pipeline(store,new CorrectingAi(),Fixtures.config(),Fixtures.CLOCK).run(false);
        assertEquals("IGNORED",store.releaseStatus(context));assertEquals("DONE",store.releaseStatus(store.release(parent.sourceIds().getFirst())));
        assertEquals(List.of(parent.sourceIds().getFirst()),SourceInputs.seeds(withContext));
    }
}
