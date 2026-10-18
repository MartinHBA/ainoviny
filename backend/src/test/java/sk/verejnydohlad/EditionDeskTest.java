package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class EditionDeskTest {
    static Article ready(MemoryStore store,String seed,String institution) {
        var fixture=Fixtures.release(seed);
        var source=new Release(fixture.id(),institution,fixture.organization(),fixture.originalUrl(),fixture.publishedAt(),fixture.collectedAt(),fixture.title(),fixture.body(),fixture.contentHash(),fixture.blobPath());
        store.collect(source);store.releaseStatus(source,"DONE",null);
        var d=new MockAi().generate(List.of(source));String title="[SYNTHETIC] "+seed;
        var claims=new ArrayList<>(d.claims());claims.set(0,new Claim(title,source.id(),source.body()));d=new Draft(title,d.perex(),d.category(),d.markdown(),claims);
        String id=Normalize.hash("edition-test|"+seed),path="drafts/"+id+"/original.md";store.putBlob("articles",path,d.markdown());
        var a=new Article(id,Normalize.slug(title)+"-"+id.substring(0,12),title,d.perex(),d.category(),Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),null,Status.DRAFT,path,
            List.of(source.id()),List.of(source.originalUrl()),"mock-offline-synthetic",null,null,null,Normalize.hash(d.markdown()),true,false,List.of(),d.claims(),"COMPLETE",0,"2026-10-12");
        a=a.state(Status.REVIEWED,Fixtures.CLOCK.instant(),"AI editor: mock-offline-editor",Editorial.approvalHash(a,d.markdown()),null,path);store.createArticle(a);
        store.operational("story|"+id,Json.write(new StoryOrder(id,new StoryPitch(source.id(),List.of(source.id()),"Čo tento dokument vysvetľuje?","ANALYSIS",90,"Synthetic fixture"),null,Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant().plus(Duration.ofDays(7)),List.of())));
        return a;
    }
    static class MergeAi extends NewsroomTest.DeskAi {
        final List<String> parents;int editionCalls,merges;boolean failOnce,rejectMerged,invalidPlan;String duplicateOf;
        MergeAi(Article a,Article b){parents=List.of(a.id(),b.id());}
        public List<StoryPitch> commission(List<Release>d,List<Article>h){return List.of();}
        public EditionPlan edition(List<EditionItem> pending,List<EditionItem> published) {
            editionCalls++;
            if(invalidPlan)return new EditionPlan(List.of(new EditionGroup("MERGE",List.of(parents.getFirst(),"invented"),null,List.of("Zlúčiť"),"Invalid synthetic IDs")));
            if(duplicateOf!=null)return new EditionPlan(List.of(new EditionGroup("DUPLICATE",List.of(parents.getFirst()),duplicateOf,List.of(),"Same event already covered")));
            if(pending.stream().map(EditionItem::id).toList().containsAll(parents))return new EditionPlan(List.of(new EditionGroup("MERGE",parents,null,List.of("Zlúčiť pohľady NKÚ, RRZ a predkladateľa; odstrániť opakovanie."),"Same concrete budget event despite different headlines")));
            return new EditionPlan(List.of());
        }
        public Draft merge(List<Draft> input,List<Release> sources,List<String> instructions) {
            merges++;if(failOnce){failOnce=false;throw new IllegalStateException("Interrupted synthetic merge");}
            String title="[SYNTHETIC] Spoločné hodnotenie rozpočtu",perex="Dve syntetické inštitúcie vysvetľujú to isté rozpočtové rozhodnutie.";
            String text="Spoločný syntetický článok zachováva stanoviská oboch inštitúcií. Každý podklad je pripísaný svojmu pôvodnému autorovi a nejde o skutočnú správu.";
            var claims=new ArrayList<Claim>();claims.add(new Claim(title,sources.getFirst().id(),sources.getFirst().body()));claims.add(new Claim(perex,sources.getFirst().id(),sources.getFirst().body()));
            for(var s:sources)claims.add(new Claim(text,s.id(),s.body()));return new Draft(title,perex,"Verejné peniaze",text,claims);
        }
        public Verification verify(Draft d,List<Release>s){if(rejectMerged && d.title().contains("Spoločné")){checks++;return new Verification(false,false,List.of("Unsupported synthetic synthesis"));}return super.verify(d,s);}
    }
    @Test void sameBudgetAcrossDifferentInstitutionsMergesBeforeEitherOriginalPublishesAndUnrelatedStoryRemains(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet a riziká NKÚ","nku-sr");var b=ready(s,"Dlh a stanovisko RRZ","rrz-sk");var other=ready(s,"Odlišné praktické pravidlá","nbs-sk");
        var ai=new MergeAi(a,b);var result=NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false);
        assertEquals(2,result.published(),result.toString());assertEquals(1,ai.editionCalls);assertEquals(1,ai.merges);assertEquals(2,s.publicArticles.size());
        assertEquals(a,s.article(a.id()).value());assertEquals(b,s.article(b.id()).value());assertEquals(Status.PUBLISHED,s.article(other.id()).value().status());
        var combined=s.article(EditionDesk.mergeId(ai.parents)).value();assertEquals(Status.PUBLISHED,combined.status());assertTrue(combined.factCheckPassed());assertEquals(2,combined.claims().stream().map(Claim::sourceId).distinct().count());assertNotNull(combined.approvedHash());
        assertTrue(s.publicArticles.values().stream().noneMatch(p->ai.parents.contains(p.id())));assertTrue(s.budget("2026-10-12").calls()<=40);
    }
    @Test void interruptedWriterLeavesBothParentsPrivateAndResumesOneAuditedJob(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet NKÚ","nku-sr");var b=ready(s,"Rozpočet RRZ","rrz-sk");var ai=new MergeAi(a,b);ai.failOnce=true;var desk=NewsroomTest.desk(s,ai);
        assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertTrue(s.publicArticles.isEmpty());
        assertEquals(1,desk.run(false,RunScope.scheduled(),false).published());assertEquals(1,ai.editionCalls);assertEquals(2,ai.merges);assertEquals(3,s.orders().size());
    }
    @Test void crashDuringRoutingCannotPublishTheUnroutedSiblingBeforeRecovery(){
        var s=new MemoryStore(){boolean once=true;@Override public void operational(String k,String v){super.operational(k,v);if(once && k.startsWith("edition-route|")){once=false;throw new IllegalStateException("Crash after first parent route");}}};
        var a=ready(s,"Rozpočet A","nku-sr");var b=ready(s,"Rozpočet B","rrz-sk");var ai=new MergeAi(a,b);var desk=NewsroomTest.desk(s,ai);
        assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(1,desk.run(false,RunScope.scheduled(),false).published());
        assertEquals(1,ai.editionCalls);assertEquals(1,ai.merges);assertTrue(s.publicArticles.values().stream().noneMatch(p->ai.parents.contains(p.id())));
    }
    @Test void failedMergedVerificationNeverFallsBackToPublishingTwoParentsOrStartsARevisionChain(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet A","nku-sr");var b=ready(s,"Rozpočet B","rrz-sk");var ai=new MergeAi(a,b);ai.rejectMerged=true;var desk=NewsroomTest.desk(s,ai);
        assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(Status.REJECTED,s.article(EditionDesk.mergeId(ai.parents)).value().status());
        var budget=s.budget("2026-10-12");assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(1,ai.merges);assertEquals(budget,s.budget("2026-10-12"));assertEquals(0,ai.feedback);
    }
    @Test void fullConceptBudgetDefersOverlappingParentsAndAdHocCanResumeWithoutResettingDailyLedger(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet A","nku-sr");var b=ready(s,"Rozpočet B","rrz-sk");var ai=new MergeAi(a,b);s.budgets.put("2026-10-12",new Budget(0,List.of("1","2","3","4","5","6")));
        var desk=NewsroomTest.desk(s,ai);assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(0,ai.merges);var daily=s.budget("2026-10-12");
        assertEquals(1,desk.run(false,RunScope.adHoc(),false).published());assertEquals(daily,s.budget("2026-10-12"));assertEquals(1,ai.merges);
    }
    @Test void sameDayPublicationInAnotherScopePreventsDuplicateAndPublishedHistoryStaysImmutable(){
        var s=new MemoryStore();var published=ready(s,"Už vydaný rozpočet","rrz-sk");var pending=ready(s,"Iný titulok tej istej udalosti","nku-sr");
        var old=s.article(published.id());var publicArticle=published.state(Status.PUBLISHED,published.reviewedAt(),published.reviewer(),published.approvedHash(),Fixtures.CLOCK.instant(),published.blobPath());s.publish(publicArticle,old.version(),RunScope.adHoc().label());
        var ai=new MergeAi(pending,published);ai.duplicateOf=published.id();assertEquals(0,NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false).published());
        assertEquals(publicArticle,s.article(published.id()).value());assertEquals(1,s.publicArticles.size());assertEquals(0,ai.merges);assertTrue(EditionDesk.routed(s,pending.id()));
    }
    @Test void previousDayCoverageDoesNotAutomaticallySuppressANewDevelopment(){
        var s=new MemoryStore();var old=ready(s,"Staršie hodnotenie rozpočtu","rrz-sk");var pending=ready(s,"Nové rozhodnutie o rozpočte","nku-sr");
        s.publish(old.state(Status.PUBLISHED,old.reviewedAt(),old.reviewer(),old.approvedHash(),Fixtures.CLOCK.instant().minus(Duration.ofDays(1)),old.blobPath()),s.article(old.id()).version(),"scheduled");
        var ai=new MergeAi(pending,old);ai.duplicateOf=old.id();assertEquals(1,NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false).published());assertEquals(0,ai.editionCalls);
    }
    @Test void invalidGroupIdsCannotSuppressOrPublishAnUnassessedBatch(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet A","nku-sr");var b=ready(s,"Rozpočet B","rrz-sk");var ai=new MergeAi(a,b);ai.invalidPlan=true;
        var result=NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false);assertEquals(0,result.published());assertFalse(result.errors().isEmpty());assertFalse(EditionDesk.routed(s,a.id()));assertTrue(s.publicArticles.isEmpty());
    }
    @Test void mergeCannotDropAnOriginalInstitutionAndInvalidProposalIsNotBoughtAgain(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet A","nku-sr");var b=ready(s,"Rozpočet B","rrz-sk");
        var ai=new MergeAi(a,b){@Override public Draft merge(List<Draft>d,List<Release>sources,List<String>instructions){merges++;return d.getFirst();}};
        var desk=NewsroomTest.desk(s,ai);assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(1,ai.merges);
        var budget=s.budget("2026-10-12");assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(1,ai.merges);assertEquals(budget,s.budget("2026-10-12"));
    }
    @Test void distinctEventsSharingBudgetWordsCanBothPublishWhenTheEditionEditorKeepsThemSeparate(){
        var s=new MemoryStore();ready(s,"Rozpočet a nové hlasovanie parlamentu","mf-sr");ready(s,"Rozpočet domácnosti a praktické úverové pravidlá","nbs-sk");
        var ai=new NewsroomTest.DeskAi();assertEquals(2,NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false).published());assertEquals(0,ai.corrections);
    }
    @Test void independentReviewRejectsBroadGovernmentPriorityMergeAndKeepsAllThreeEventsSeparate(){
        var s=new MemoryStore();var jess=ready(s,"JESS pripravované vlastníctvo","uv-sr");var foreign=ready(s,"Zahraničná politika vlády","uv-sr");var martin=ready(s,"Martin hrubá stavba nemocnice","uv-sr");
        var ai=new MergeAi(jess,foreign){
            public EditionPlan reviewEdition(EditionPlan p,List<EditionItem> pending,List<EditionItem> published){
                assertEquals(3,pending.size());assertEquals(1,p.groups().size());return new EditionPlan(List.of());
            }
        };
        assertEquals(3,NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false).published());
        assertEquals(0,ai.merges);assertFalse(EditionDesk.routed(s,jess.id()));assertFalse(EditionDesk.routed(s,foreign.id()));assertFalse(EditionDesk.routed(s,martin.id()));
    }
    @Test void legacyChainHoldsAreReassessedByNewPolicyWithoutReleasingMergeOrDuplicateParents(){
        var s=new MemoryStore();var jess=ready(s,"JESS uložená syntéza","uv-sr");var foreign=ready(s,"Samostatná zahraničná politika","uv-sr");var parent=ready(s,"Pôvodný súkromný koncept","uv-sr");
        String hold=Json.write(Map.of("action","HOLD","plan","old-plan","reason","Further consolidation requires a new editorial assignment"));
        s.operational("edition-route|"+jess.id(),hold);s.operational("edition-route|"+foreign.id(),hold);
        s.operational("merge|"+jess.id(),"{}");
        String route=Json.write(Map.of("action","MERGE","articleId",jess.id()));s.operational("edition-route|"+parent.id(),route);
        var ai=new MergeAi(jess,foreign){public EditionPlan reviewEdition(EditionPlan p,List<EditionItem>a,List<EditionItem>b){return new EditionPlan(List.of());}};
        assertEquals(2,NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),false).published());
        assertEquals(0,ai.merges);assertEquals(parent,s.article(parent.id()).value());assertEquals(route,s.operational("edition-route|"+parent.id()));
        var reassessed=Json.tree(s.operational("edition-route|"+jess.id()));assertEquals("DISTINCT",reassessed.path("action").asText());
        assertEquals("HOLD",reassessed.path("previous").path("action").asText());assertEquals(EditionDesk.SELECTION_POLICY,reassessed.path("policy").asText());
    }
    @Test void interruptedRoutingReviewReusesProposalButCannotRouteOrPublishBeforeReviewCompletes(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet nový návrh","mf-sr");var b=ready(s,"Rozpočet nezávislé hodnotenie","rrz-sk");int[] routingReviews={0};
        var ai=new MergeAi(a,b){public EditionPlan reviewEdition(EditionPlan p,List<EditionItem>x,List<EditionItem>y){if(routingReviews[0]++==0)throw new IllegalStateException("Interrupted review");return p;}};
        var desk=NewsroomTest.desk(s,ai);assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());
        assertFalse(EditionDesk.routed(s,a.id()));assertFalse(EditionDesk.routed(s,b.id()));assertEquals(0,ai.merges);
        assertEquals(1,desk.run(false,RunScope.scheduled(),false).published());assertEquals(1,ai.editionCalls);assertEquals(2,routingReviews[0]);assertEquals(1,ai.merges);
    }
    @Test void exhaustionBetweenProposalAndReviewLeavesBatchPrivateAndReviewCannotInventOrModifyGroups(){
        var s=new MemoryStore();var a=ready(s,"Rozpočet A","mf-sr");var b=ready(s,"Rozpočet B","rrz-sk");var ai=new MergeAi(a,b);
        var config=NewsroomTest.config("MAX_AI_CALLS_PER_DAY","1");
        assertEquals(0,NewsroomTest.desk(s,ai,config,Fixtures.CLOCK,PublicationCheck.offline()).run(false,RunScope.scheduled(),false).published());
        assertEquals(1,s.budget("2026-10-12").calls());assertFalse(EditionDesk.routed(s,a.id()));assertEquals(0,ai.merges);
        var group=new EditionGroup("MERGE",List.of(a.id(),b.id()),null,List.of("Pôvodné pokyny"),"Tá istá udalosť");
        var changed=new EditionGroup("MERGE",group.articleIds(),null,List.of("Nové pokyny"),group.reason());
        assertThrows(IllegalArgumentException.class,()->EditionDesk.validateReview(new EditionPlan(List.of(group)),new EditionPlan(List.of(changed))));
    }
    @Test void fullEditionDoesNotSpendOnAnotherSelectionEvenWhenReserveArticlesRemain(){
        var s=NewsroomTest.sources(4);var ai=new NewsroomTest.DeskAi();NewsroomTest.desk(s,ai).run(false,RunScope.scheduled(),true);
        var next=Clock.offset(Fixtures.CLOCK,Duration.ofDays(1));var desk=NewsroomTest.desk(s,ai,NewsroomTest.config(),next,PublicationCheck.offline());
        assertEquals(3,desk.run(false,RunScope.scheduled(),false).published());var budget=s.budget("2026-10-13");assertEquals(7,budget.calls());
        assertEquals(0,desk.run(false,RunScope.scheduled(),false).published());assertEquals(budget,s.budget("2026-10-13"));assertEquals(1,s.editorial().stream().filter(a->a.status()==Status.REVIEWED).count());
    }
    @Test void oneArticleCannotAppearInTwoGroupsAndPublishedIdsCannotBeMergeInputs(){
        var a=new EditionItem("a","Titulok A","Perex A","Politika","Telo A",List.of("s"));var b=new EditionItem("b","Titulok B","Perex B","Politika","Telo B",List.of("t"));
        var merge=new EditionGroup("MERGE",List.of("a","b"),null,List.of("Zlúčiť"),"Same event");
        assertThrows(IllegalArgumentException.class,()->EditionDesk.validate(new EditionPlan(List.of(merge,merge)),List.of(a,b),List.of()));
        assertThrows(IllegalArgumentException.class,()->EditionDesk.validate(new EditionPlan(List.of(merge)),List.of(a),List.of(b)));
    }
}
