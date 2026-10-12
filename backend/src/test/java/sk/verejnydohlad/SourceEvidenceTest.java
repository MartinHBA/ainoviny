package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceEvidenceTest {
    @Test void everyEvidenceIdResolvesToAnUnalteredBoundedContiguousOriginal() {
        var r=Fixtures.release("excerpts");String body="A complete source sentence with factual context. ".repeat(300);
        var source=new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt(),r.collectedAt(),r.title(),body,Normalize.hash(body),r.blobPath());
        var excerpts=SourceEvidence.excerpts(source);assertTrue(excerpts.size()>2);
        assertEquals(excerpts.size(),excerpts.stream().map(SourceEvidence.Excerpt::id).distinct().count());
        for(var excerpt:excerpts) {
            assertTrue(excerpt.text().length()>=10);assertTrue(excerpt.text().length()<=1800);
            if(excerpt.id().equals("title")) assertEquals(source.title(),excerpt.text());
            else { assertTrue(excerpt.text().length()<=1200);assertTrue(body.substring(0,10000).contains(excerpt.text())); }
        }
        assertEquals(excerpts,SourceEvidence.excerpts(source));
    }
    @Test void unknownSourceOrEvidenceCannotAcquireInventedGrounding() {
        var r=Fixtures.release("unknown-evidence");var d=new MockAi().generate(List.of(r));
        for(var invalid:List.of(new GenerationClaim(d.markdown(),"invented-source","body-0"),new GenerationClaim(d.markdown(),r.id(),"invented-excerpt"))) {
            var generation=new Generation(new GenerationClaim(d.title(),r.id(),"body-0"),new GenerationClaim(d.perex(),r.id(),"body-0"),d.category(),List.of(invalid));
            assertThrows(IllegalArgumentException.class,() -> SourceEvidence.assemble(generation,List.of(r)));
        }
    }
    @Test void selectingRealEvidenceDoesNotClearUnsupportedNumbers() {
        var r=Fixtures.release("number-guard");var d=new MockAi().generate(List.of(r));
        var generation=new Generation(new GenerationClaim(d.title(),r.id(),"body-0"),new GenerationClaim(d.perex(),r.id(),"body-0"),d.category(),
            List.of(new GenerationClaim(d.markdown()+" Nepodložená suma je 999 eur.",r.id(),"body-0")));
        var draft=SourceEvidence.assemble(generation,List.of(r));
        assertEquals(r.body(),draft.claims().getLast().evidence());
        assertFalse(Validation.grounded(draft,List.of(r),new Verification(true,false,List.of())).passed());
    }
}
