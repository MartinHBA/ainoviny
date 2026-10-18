package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ReeditionTest {
    @Test void legacyArticleJsonKeepsItsApprovalAndOnlyRevisionFieldCanBeMissing(){
        var s=new MemoryStore();var old=publish(s,"Pred migráciou",Fixtures.CLOCK.instant());
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)Json.tree(Json.write(old));json.remove("editionRevision");
        var restored=Json.read(json.toString(),Article.class);assertEquals(old,restored);assertEquals(old.approvedHash(),Editorial.approvalHash(restored,s.blob("articles",restored.blobPath())));
        json.remove("claims");assertThrows(IllegalArgumentException.class,()->Json.read(json.toString(),Article.class));
    }
    @Test void revisionIdentityStillDistinguishesPrimaryDocumentFromResearchContext(){
        var s=new MemoryStore();var primary=Fixtures.release("revision-primary");var context=Fixtures.release("revision-context");s.collect(primary);s.collect(context);
        var ai=new NewsroomTest.DeskAi(){public List<StoryPitch> commission(List<Release>d,List<Article>h){return List.of(new StoryPitch(primary.id(),List.of(primary.id()),"Čo uvádza hlavný dokument?","NEWS",90,"Primary only"));}};
        ResearchLookup lookup=(p,d,seeds,st,g,end)->new ResearchLookup.Result(List.of(primary,context),List.of());
        var scope=RunScope.adHoc();new Reedition(s,ai,NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1"),Fixtures.CLOCK,lookup,(st,g,end)->new Newsroom.Discovery.Result(0,0,List.of()),PublicationCheck.offline()).run(false,scope,false);
        var article=s.article(s.activeRevision("2026-10-12").articleIds().getFirst()).value();assertEquals(2,article.sourceIds().size());assertEquals(List.of(primary.id()),SourceInputs.seeds(article));
    }
    static Reedition desk(Store s,Ai ai,Config c,Clock clock){return new Reedition(s,ai,c,clock,ResearchLookup.archiveOnly(),(st,g,end)->new Newsroom.Discovery.Result(0,0,List.of()),PublicationCheck.offline());}
    static Article publish(MemoryStore s,String name,Instant when){var a=EditionDeskTest.ready(s,name,"synthetic");var p=Models.withEdition(a.state(Status.PUBLISHED,a.reviewedAt(),a.reviewer(),a.approvedHash(),when,a.blobPath()),when.atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString());s.publish(p,s.article(a.id()).version());return p;}
    static NewsroomTest.DeskAi single(Article original){return new NewsroomTest.DeskAi(){public List<StoryPitch> commission(List<Release> docs,List<Article> history){planning++;assertTrue(history.stream().noneMatch(a->a.id().equals(original.id())));return List.of(new StoryPitch(original.sourceIds().getFirst(),original.sourceIds(),"Čo uvádza aktuálny podklad?","NEWS",90,"New cycle"));}};}
    @Test void freshProcessKeepsOldVisibleUntilCompletionThenReplacesTodayAndPreservesYesterday(){
        var s=new MemoryStore();var old=publish(s,"Dnešný pôvodný článok",Fixtures.CLOCK.instant());var yesterday=publish(s,"Včerajší článok",Fixtures.CLOCK.instant().minusSeconds(86400));
        var privateOld=EditionDeskTest.ready(s,"Dnešná stará príprava","synthetic");
        s.updateArticle(Models.withEdition(privateOld,"2026-10-11"),s.article(privateOld.id()).version()); // Carried-over legacy reserve also belongs to the reset.
        s.budgets.put("2026-10-12",new Budget(40,List.of("1","2","3","4","5","6")));var daily=s.budget("2026-10-12");
        var ai=single(old);var guarded=new NewsroomTest.DeskAi(){
            public List<StoryPitch> commission(List<Release>d,List<Article>h){return ai.commission(d,h);}
            public EditorDecision review(Draft d,List<Release>r){assertNotNull(s.bySlug(old.slug()));assertEquals(2,s.publicArticles.size());return super.review(d,r);}
        };
        var scope=RunScope.adHoc();var result=desk(s,guarded,NewsroomTest.config(),Fixtures.CLOCK).run(false,scope,false);
        assertEquals(1,result.published(),result.toString());var revision=s.revision(scope.id());assertEquals("ACTIVE",revision.state());assertEquals(1,revision.articleIds().size());
        var fresh=s.article(revision.articleIds().getFirst()).value();assertNotEquals(old.id(),fresh.id());assertEquals(old.sourceIds(),fresh.sourceIds());assertNotEquals(old.approvedHash(),fresh.approvedHash());
        assertNull(s.bySlug(old.slug()));assertEquals(Status.SUPERSEDED,s.article(old.id()).value().status());assertEquals(Status.CANCELLED,s.article(privateOld.id()).value().status());
        assertNotNull(s.bySlug(yesterday.slug()));assertNotNull(s.bySlug(fresh.slug()));assertEquals(2,s.publicArticles.size());assertEquals(daily,s.budget("2026-10-12"));
        assertTrue(fresh.blobPath().startsWith("reeditions/"+scope.id()+"/"));assertFalse(s.blob("articles",fresh.blobPath()).isBlank());
        var before=s.budget("edition|2026-10-12|"+scope.id());assertEquals(0,desk(s,guarded,NewsroomTest.config(),Fixtures.CLOCK).run(false,scope,false).published());assertEquals(before,s.budget("edition|2026-10-12|"+scope.id()));
    }
    @Test void noApprovedArticlesLeavesOriginalAndItsPreparationIntact(){
        var s=new MemoryStore();var old=publish(s,"Pôvodné vydanie",Fixtures.CLOCK.instant());var ready=EditionDeskTest.ready(s,"Rozpracovaná zásoba","synthetic");
        var ai=single(old);ai.sensitive=true;var scope=RunScope.adHoc();assertEquals(0,desk(s,ai,NewsroomTest.config(),Fixtures.CLOCK).run(false,scope,false).published());
        assertEquals("NO_APPROVED_ARTICLES",s.revision(scope.id()).state());assertNull(s.activeRevision("2026-10-12"));assertEquals(old,s.bySlug(old.slug()));assertEquals(ready,s.article(ready.id()).value());
        assertEquals(1,s.publicArticles.size());
    }
    @Test void failedAtomicActivationKeepsStagedContentPrivateAndRetryReusesSameCycle(){
        var s=new MemoryStore();var old=publish(s,"Pôvodné bezpečné vydanie",Fixtures.CLOCK.instant());var ai=single(old);var scope=RunScope.adHoc();s.failActivate=true;
        assertFalse(desk(s,ai,NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1"),Fixtures.CLOCK).run(false,scope,false).errors().isEmpty());
        assertEquals(old,s.bySlug(old.slug()));assertEquals(1,s.publicArticles.size());assertNull(s.activeRevision("2026-10-12"));
        var staged=s.editorial().stream().filter(a->scope.id().equals(a.editionRevision()) && a.status()==Status.PUBLISHED).findFirst().orElseThrow();assertNull(s.bySlug(staged.slug()));
        var before=s.budget("edition|2026-10-12|"+scope.id());s.failActivate=false;
        desk(s,ai,NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1"),Fixtures.CLOCK).run(false,scope,false);
        assertEquals("ACTIVE",s.revision(scope.id()).state());assertEquals(before,s.budget("edition|2026-10-12|"+scope.id()));assertNotNull(s.bySlug(staged.slug()));assertNull(s.bySlug(old.slug()));
    }
    @Test void anotherManualCycleHasFreshIdsAndOldCycleCannotReactivate(){
        var s=new MemoryStore();var old=publish(s,"Recyklovateľný dnešný podklad",Fixtures.CLOCK.instant());var ai=single(old);var c=NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1");
        var first=RunScope.adHoc();desk(s,ai,c,Fixtures.CLOCK).run(false,first,false);var firstId=s.revision(first.id()).articleIds().getFirst();
        var second=RunScope.adHoc();desk(s,ai,c,Fixtures.CLOCK).run(false,second,false);var secondId=s.revision(second.id()).articleIds().getFirst();
        assertNotEquals(firstId,secondId);assertEquals("SUPERSEDED",s.revision(first.id()).state());assertEquals(Status.SUPERSEDED,s.article(firstId).value().status());assertEquals(second.id(),s.activeRevision("2026-10-12").id());
        assertFalse(desk(s,ai,c,Fixtures.CLOCK).run(false,first,false).errors().isEmpty());assertEquals(second.id(),s.activeRevision("2026-10-12").id());assertEquals(1,s.publicArticles.size());
    }
    @Test void laterRetryRefreshesStagedApprovalsAndUsesActualSwapTime(){
        var s=new MemoryStore();var old=publish(s,"Článok pred oneskorenou výmenou",Fixtures.CLOCK.instant());var ai=single(old);var scope=RunScope.adHoc();var c=NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1");s.failActivate=true;
        desk(s,ai,c,Fixtures.CLOCK).run(false,scope,false);var before=s.budget("edition|2026-10-12|"+scope.id());
        s.failActivate=false;var later=Clock.offset(Fixtures.CLOCK,Duration.ofHours(2));desk(s,ai,c,later).run(false,scope,false);
        var after=s.budget("edition|2026-10-12|"+scope.id());assertEquals(before.calls()+2,after.calls());assertEquals(before.articleIds(),after.articleIds());
        var article=s.article(s.activeRevision("2026-10-12").articleIds().getFirst()).value();assertEquals(later.instant(),article.publishedAt());assertNull(s.bySlug(old.slug()));
    }
    @Test void timersUseActiveCycleBudgetAndCannotPublishBeyondItsQuotaOrResumeCancelledWork(){
        var s=new MemoryStore();var old=publish(s,"Vydanie pred reedíciou",Fixtures.CLOCK.instant());var oldPrivate=EditionDeskTest.ready(s,"Pôvodný súkromný koncept","synthetic");
        var ai=single(old);var c=NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1");var scope=RunScope.adHoc();desk(s,ai,c,Fixtures.CLOCK).run(false,scope,false);
        var before=s.budget("edition|2026-10-12|"+scope.id());desk(s,ai,c,Fixtures.CLOCK).run(false,RunScope.scheduled(),false);
        assertEquals(before,s.budget("edition|2026-10-12|"+scope.id()));assertEquals(1,s.publicArticles.size());assertEquals(Status.CANCELLED,s.article(oldPrivate.id()).value().status());
        try(var guard=s.lock()) {var nextDay=new EditionWorkStore(s,null,guard,false);assertTrue(nextDay.editorial().stream().noneMatch(a->a.editionRevision()!=null && a.status()!=Status.PUBLISHED));}
    }
    @Test void interruptedCycleCannotReplaceANewerEditionOrCrossLocalMidnight(){
        var s=new MemoryStore();var old=publish(s,"Dnešné pôvodné vydanie",Fixtures.CLOCK.instant());var ai=single(old);var c=NewsroomTest.config("MAX_PUBLISHED_ARTICLES_PER_DAY","1");
        var stale=RunScope.adHoc();s.revision(new EditionRevision(stale.id(),"2026-10-12","BUILDING",null,Fixtures.CLOCK.instant(),null,List.of(),List.of()));
        var current=RunScope.adHoc();desk(s,ai,c,Fixtures.CLOCK).run(false,current,false);
        assertFalse(desk(s,ai,c,Fixtures.CLOCK).run(false,stale,false).errors().isEmpty());assertEquals(current.id(),s.activeRevision("2026-10-12").id());
        assertFalse(desk(s,ai,c,Clock.offset(Fixtures.CLOCK,Duration.ofDays(1))).run(false,stale,false).errors().isEmpty());
    }
}
