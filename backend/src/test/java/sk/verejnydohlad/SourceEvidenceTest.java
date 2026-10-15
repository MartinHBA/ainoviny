package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceEvidenceTest {
    @Test void comparisonParagraphUsesBothOriginalsButCannotBorrowNumbersFromAnotherParagraph() {
        var a=ResearchTest.document("multi-a","mf-sr","Syntetický rozpočet 2027","Ministerstvo uvádza deficit 3 percentá. "+"Syntetický finančný podklad. ".repeat(5));
        var b=ResearchTest.document("multi-b","rrz-sk","Syntetické hodnotenie 2027","RRZ odhaduje deficit 4 percentá. "+"Syntetický nezávislý podklad. ".repeat(5));
        var refs=List.of(new EvidenceRef(a.id(),"body-0"),new EvidenceRef(b.id(),"body-0"));
        var title=new ResearchedClaim("Syntetické porovnanie rozpočtu",refs);
        var comparison=new ResearchedClaim("Ministerstvo uvádza deficit 3 percentá, kým RRZ odhaduje 4 percentá. Ide o dve odlišné hodnotenia syntetického rozpočtu.",refs);
        var draft=SourceEvidence.assemble(new ResearchedGeneration(title,comparison,"Verejné peniaze",List.of(comparison)),List.of(a,b));
        assertTrue(Validation.grounded(draft,List.of(a,b),new Verification(true,false,List.of())).passed());assertEquals(6,draft.claims().size());
        var unsupported=new ResearchedClaim(comparison.text(),List.of(refs.getFirst()));
        var invalid=SourceEvidence.assemble(new ResearchedGeneration(title,comparison,"Verejné peniaze",List.of(new ResearchedClaim(unsupported.text()+" Samostatné tvrdenie.",unsupported.citations()))),List.of(a,b));
        assertFalse(Validation.grounded(invalid,List.of(a,b),new Verification(true,false,List.of())).passed());
    }
    @Test void targetedResearchCanCiteRelevantContiguousEvidenceBeyondWriterFirstTenThousandCharacters() {
        String body="Úvodná všeobecná informácia. ".repeat(500)+" Záverečné fiškálne riziká dlhu dosahujú 77 percent podľa syntetického podkladu.";
        var source=ResearchTest.document("late-evidence","rrz-sk","Syntetický podklad",body);
        var excerpt=SourceEvidence.researchExcerpts(source,"fiškálne riziká dlhu").stream().filter(e -> e.text().contains("77")).findFirst().orElseThrow();
        assertTrue(source.body().contains(excerpt.text()));assertFalse(SourceEvidence.excerpts(source).stream().anyMatch(e -> e.text().contains("77")));
        var claim=new ResearchedClaim("Syntetický podklad uvádza fiškálne riziká 77 percent.",List.of(new EvidenceRef(source.id(),excerpt.id())));
        var draft=SourceEvidence.assemble(new ResearchedGeneration(claim,claim,"Verejné peniaze",List.of(new ResearchedClaim(claim.text()+" Ide o syntetickú skúšku neskoršej pasáže dokumentu.",claim.citations()))),List.of(source));
        assertTrue(Validation.grounded(draft,List.of(source),new Verification(true,false,List.of())).passed());
    }
    @Test void commissionedFirstConceptCanResolveTheLateExcerptOfferedByItsTargetedWriter() {
        var source=ResearchTest.document("commission-late","rrz-sk","Syntetická analýza","Úvodná všeobecná informácia. ".repeat(600)+" Záverečné fiškálne riziká dosahujú 77 percent.");
        var evidence=SourceEvidence.researchExcerpts(source,"fiškálne riziká").stream().filter(e->e.text().contains("77")).findFirst().orElseThrow();
        var field=new GenerationClaim("Syntetická analýza uvádza riziká 77 percent.",source.id(),evidence.id());
        var d=SourceEvidence.assemble(new Generation(field,field,"Verejné peniaze",List.of(new GenerationClaim(field.text()+" Ide o skúšku presnej neskoršej pasáže pôvodného dokumentu.",source.id(),evidence.id()))),List.of(source));
        assertTrue(Validation.grounded(d,List.of(source),new Verification(true,false,List.of())).passed());
    }
    @Test void numericalCitationCanUseAnotherProvidedContiguousExcerptFromSameDocument() {
        var fixture=Fixtures.release("numeric-selection");String text="Parlament schválil rozpočet na roky 2027 až 2029.";
        String body=text+" "+"Schválený rozpočet obsahuje verejné výdavky a príjmy. ".repeat(80);
        var source=new Release(fixture.id(),fixture.sourceId(),fixture.organization(),fixture.originalUrl(),fixture.publishedAt(),fixture.collectedAt(),
            "Parlament schválil rozpočet",body,Normalize.hash(body),fixture.blobPath());
        var claim=new GenerationClaim(text,source.id(),"title");
        var draft=SourceEvidence.assemble(new Generation(claim,claim,"Verejné peniaze",List.of(new GenerationClaim(text+" Parlament o rozpočte rozhodol na rokovaní.",source.id(),"body-0"))),List.of(source));
        assertTrue(draft.claims().getFirst().evidence().contains("2029"));assertTrue(body.contains(draft.claims().getFirst().evidence()));
        assertTrue(Validation.grounded(draft,List.of(source),new Verification(true,false,List.of())).passed());
    }
    @Test void numericalCitationNeverBorrowsEvidenceFromAnotherDocumentOrStitchesDistantSpans() {
        var fixture=Fixtures.release("same-source-only");String body="Zdroj uvádza rok 2027. "+"Samostatný odstavec s kontextom. ".repeat(100)+" Zdroj uvádza rok 2029.";
        var source=new Release(fixture.id(),fixture.sourceId(),fixture.organization(),fixture.originalUrl(),fixture.publishedAt(),fixture.collectedAt(),
            "Parlament schválil rozpočet",body,Normalize.hash(body),fixture.blobPath());
        var other=ResearchTest.document("other-years","other","Roky 2027 až 2029","Druhý nezávislý podklad uvádza roky 2027 až 2029 spoločne.");
        String text="Parlament schválil rozpočet na roky 2027 až 2029.";var claim=new GenerationClaim(text,source.id(),"title");
        var draft=SourceEvidence.assemble(new Generation(claim,claim,"Verejné peniaze",List.of(new GenerationClaim(text+" Ide o rozpočtové rozhodnutie parlamentu.",source.id(),"body-0"))),List.of(source,other));
        assertEquals(source.title(),draft.claims().getFirst().evidence());assertFalse(Validation.grounded(draft,List.of(source,other),new Verification(true,false,List.of())).passed());
    }
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
