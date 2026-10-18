package sk.verejnydohlad;

import java.util.*;
import org.junit.jupiter.api.Test;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicApiTest {
    static Article sources(Article a,List<String> ids,List<String> urls,List<Claim> claims){
        return new Article(a.id(),a.slug(),a.title(),a.perex(),a.category(),a.createdAt(),a.generatedAt(),a.publishedAt(),a.status(),a.blobPath(),ids,urls,a.aiModel(),a.reviewedAt(),a.reviewer(),a.approvedHash(),a.contentHash(),a.factCheckPassed(),a.sensitive(),a.issues(),claims,a.phase(),a.editorialOrder(),a.editionDate(),a.editionRevision());
    }
    @Test void publicDetailExposesOnlyCitedOriginalAndKeepsUnusedRrzResearchPrivateWithoutEditingArticle(){
        var s=new MemoryStore();var a=EditionDeskTest.ready(s,"Mochovce primary","mh-sr");var rrz=Fixtures.release("unused-RRZ-budget");var nku=Fixtures.release("unused-NKU-training");
        s.collect(rrz);s.collect(nku);var original=s.release(a.sourceIds().getFirst());
        a=sources(a,List.of(original.id(),rrz.id(),nku.id()),List.of(original.originalUrl(),rrz.originalUrl(),nku.originalUrl()),a.claims());
        a=a.state(Status.PUBLISHED,a.reviewedAt(),a.reviewer(),Editorial.approvalHash(a,s.blob("articles",a.blobPath())),Fixtures.CLOCK.instant(),a.blobPath());s.publish(a,s.article(a.id()).version());
        var before=s.article(a.id());var detail=new PublicApi(s).detail(a.slug());
        assertEquals(List.of(original.originalUrl()),detail.originalSources());assertEquals(3,s.article(a.id()).value().sourceUrls().size());assertEquals(before,s.article(a.id()));
    }
    @Test void allActuallyCitedSourcesAreReturnedOnceInOriginalOrderIncludingSecondSourceForOneParagraph(){
        var s=new MemoryStore();var a=EditionDeskTest.ready(s,"multi-source article","mf-sr");var second=Fixtures.release("actual-RRZ-assessment");var unused=Fixtures.release("unused-research");
        String firstId=a.sourceIds().getFirst(),firstUrl=a.sourceUrls().getFirst();var claims=new ArrayList<>(a.claims());claims.add(new Claim(a.claims().getLast().text(),second.id(),second.body()));claims.add(claims.getLast());
        a=sources(a,List.of(firstId,unused.id(),second.id()),List.of(firstUrl,unused.originalUrl(),second.originalUrl()),claims);
        assertEquals(List.of(firstUrl,second.originalUrl()),PublicApi.citedSources(a));
    }
    @Test void missingConflictingOrUnmappedCitationsCannotFallBackToShowingTheEntireResearchPack(){
        var s=new MemoryStore();var a=EditionDeskTest.ready(s,"invalid public citations","mh-sr");var valid=a;
        var unknown=new ArrayList<>(a.claims());unknown.add(new Claim("Unknown source",Normalize.hash("missing"),"Missing evidence"));
        assertThrows(IllegalStateException.class,()->PublicApi.citedSources(sources(valid,valid.sourceIds(),valid.sourceUrls(),unknown)));
        assertThrows(IllegalStateException.class,()->PublicApi.citedSources(sources(valid,valid.sourceIds(),List.of(),valid.claims())));
        assertThrows(IllegalStateException.class,()->PublicApi.citedSources(sources(valid,valid.sourceIds(),valid.sourceUrls(),List.of())));
        assertThrows(IllegalStateException.class,()->PublicApi.citedSources(sources(valid,List.of(valid.sourceIds().getFirst(),valid.sourceIds().getFirst()),List.of(valid.sourceUrls().getFirst(),"https://example.invalid/conflict"),valid.claims())));
    }
}
