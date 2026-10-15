package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftRepairTest {
    static Draft draft(Release source,String text) {
        return new Draft("Syntetické hodnotenie dokumentu","Syntetický podklad vysvetľuje verejné rozhodnutie.","Verejné peniaze",text,
            List.of(new Claim("Syntetické hodnotenie dokumentu",source.id(),source.body()),new Claim("Syntetický podklad vysvetľuje verejné rozhodnutie.",source.id(),source.body()),new Claim(text,source.id(),source.body())));
    }
    @Test void exactDecimalEqualityDoesNotRoundOrInterpretMultiSeparatorTokens() {
        assertTrue(EvidenceNumbers.missing("32 miliónov a 0,25 minúty","32,0 milióna a 0.250 minúty").isEmpty());
        assertEquals(List.of("32,1","0,251"),EvidenceNumbers.missing("32,1 a 0,251","32 a 0,25"));
        assertEquals(List.of("1.000.000"),EvidenceNumbers.missing("1.000.000","1000000"));
        var source=ResearchTest.document("decimal-check","mf-sr","Syntetické hodnotenie","Úprava stojí 32,0 milióna eur. "+"Syntetický kontext dokumentu. ".repeat(4));
        assertTrue(Validation.grounded(draft(source,"Úprava stojí 32 miliónov eur. "+"Syntetický kontext vysvetlenia. ".repeat(3)),List.of(source),new Verification(true,false,List.of())).passed());
        assertDoesNotThrow(()->Research.validate(new ResearchBrief(false,false,"Otázka",List.of(new GenerationClaim("Úprava stojí 32 miliónov eur.",source.id(),"body-0")),List.of(),List.of()),List.of(source)));
    }
    @Test void researchedExcerptSelectionRepairsLateSameSourceEvidenceButNeverBorrowsUncitedSource() {
        var source=ResearchTest.document("repair-late","nbs-sk","Syntetické pravidlá","Úvodný všeobecný kontext. ".repeat(600)+" Pravidlá začnú platiť v roku 2027.");
        var other=ResearchTest.document("repair-other","rrz-sk","Iný dokument","Iná inštitúcia uvádza limit 70 percent. "+"Syntetický kontext. ".repeat(5));
        var refs=List.of(new EvidenceRef(source.id(),"body-0"));
        var field=new ResearchedClaim("Pravidlá začnú platiť v roku 2027.",refs);
        var body=new ResearchedClaim(field.text()+" Ide o syntetickú skúšku citovania neskoršej pasáže dokumentu.",refs);
        var d=SourceEvidence.assemble(new ResearchedGeneration(field,field,"Slovensko",List.of(body)),List.of(source,other));
        assertTrue(d.claims().stream().anyMatch(c->c.evidence().contains("2027")));
        assertTrue(Validation.grounded(d,List.of(source,other),new Verification(true,false,List.of())).passed());
        var bad=new ResearchedClaim(body.text()+" Limit je 70 percent.",refs);
        var invalid=SourceEvidence.assemble(new ResearchedGeneration(field,field,"Slovensko",List.of(bad)),List.of(source,other));
        assertFalse(Validation.grounded(invalid,List.of(source,other),new Verification(true,false,List.of())).passed());
    }
    @Test void preflightBuysOneCorrectionAndReusesItAfterInterruptionWithoutApprovingPublication() {
        var source=Fixtures.release("preflight");var invalid=draft(source,"Syntetický článok obsahuje nepodloženú sumu 999 eur. "+"Kontext pôvodného dokumentu. ".repeat(3));
        var store=new MemoryStore();int[] paid={0};
        var ai=new NewsroomTest.DeskAi(){@Override public Draft correct(Draft d,List<Release> s,List<String> issues){corrections++;return new MockAi().generate(s);}};
        var payer=new DraftRepair.Paid(){public <T>T call(Supplier<T> action){paid[0]++;return action.get();}};
        try(var guard=store.lock()) {
            var repaired=DraftRepair.run("test",invalid,List.of(source),false,store,ai,guard,payer);
            assertTrue(Validation.grounded(repaired,List.of(source),new Verification(true,false,List.of())).passed());
            assertEquals(repaired,DraftRepair.run("test",invalid,List.of(source),false,store,ai,guard,payer));
            assertEquals(1,paid[0]);assertEquals(1,ai.corrections);assertEquals(0,ai.checks);assertEquals(0,ai.reviews);assertTrue(store.publicArticles.isEmpty());
        }
    }
    @Test void incompleteInstitutionPerspectiveIsNotAcceptedOrRepeatedAndSensitiveDraftsAreNotRepaired() {
        var a=ResearchTest.document("repair-mf","mf-sr","Syntetický rozpočet",Fixtures.release("a").body());
        var b=ResearchTest.document("repair-rrz","rrz-sk","Syntetické stanovisko",Fixtures.release("b").body());
        var original=draft(a,"Syntetický článok uvádza nepodložených 999 eur. "+"Vysvetlenie dokumentu. ".repeat(3));
        var claims=new ArrayList<>(original.claims());claims.add(new Claim(original.markdown(),b.id(),b.body()));
        var both=new Draft(original.title(),original.perex(),original.category(),original.markdown(),claims);
        var store=new MemoryStore();var ai=new NewsroomTest.DeskAi(){@Override public Draft correct(Draft d,List<Release>s,List<String>i){corrections++;return draft(a,a.body());}};
        var payer=new DraftRepair.Paid(){public<T>T call(Supplier<T>action){return action.get();}};
        try(var guard=store.lock()) {
            assertEquals(both,DraftRepair.run("test",both,List.of(a,b),false,store,ai,guard,payer));
            assertEquals(both,DraftRepair.run("test",both,List.of(a,b),false,store,ai,guard,payer));assertEquals(1,ai.corrections);
            DraftRepair.run("sensitive",both,List.of(a,b),true,store,ai,guard,payer);assertEquals(1,ai.corrections);
        }
    }
    @Test void actualNewsroomRepairsInsideOneConceptThenPerformsBothVerificationStagesAndEditorReviews() {
        var store=NewsroomTest.sources(1);
        var ai=new NewsroomTest.DeskAi(){
            @Override public Draft rewrite(Draft d,List<Release>s,ResearchBrief b){return draft(s.getFirst(),"Syntetická nepodložená suma je 999 eur. "+"Vysvetlenie pôvodného dokumentu. ".repeat(3));}
            @Override public Draft correct(Draft d,List<Release>s,List<String>issues){corrections++;return new MockAi().generate(s);}
        };
        assertEquals(1,NewsroomTest.desk(store,ai).run(false,RunScope.scheduled(),false).published());
        assertEquals(1,store.editorial().size());assertEquals(1,store.budget("2026-10-12").articleIds().size());
        assertEquals(1,ai.corrections);assertEquals(2,ai.checks);assertEquals(2,ai.reviews);assertEquals(0,ai.feedback);
    }
}
