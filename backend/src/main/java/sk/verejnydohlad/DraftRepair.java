package sk.verejnydohlad;

import java.util.*;
import java.util.function.Supplier;
import sk.verejnydohlad.Models.*;

/** One cached correction inside a concept, before verification; never an approval. */
final class DraftRepair {
    static final String POLICY="numeric-preflight-v1";
    interface Paid {<T> T call(Supplier<T> action);}
    static Draft run(String articleId,Draft original,List<Release> sources,boolean sensitive,Store store,Ai ai,Store.Guard guard,Paid paid) {
        Draft aligned=SourceEvidence.align(original,sources);
        var check=Validation.grounded(aligned,sources,new Verification(true,sensitive,List.of()));
        if(check.passed() || check.sensitive() || check.issues().stream().anyMatch(i->!i.startsWith("Numeric claim is not grounded: ")))return aligned;
        String hash=Normalize.hash(Json.write(List.of(aligned,sources,ai.editorModel(),POLICY)));
        String base="draft-repairs/"+articleId+"/"+hash+"/";
        String cached=store.optionalBlob("articles",base+"proposal.json");
        Draft proposal=cached==null?paid.call(()->ai.correct(aligned,sources,check.issues())):Json.read(cached,Draft.class);
        // Save before perspective/grounding checks, so a rejected proposal cannot buy another attempt.
        guard.check();if(cached==null)store.putBlob("articles",base+"proposal.json",Json.write(proposal));
        Validation.draft(proposal);
        if(!preservesPerspectives(aligned,proposal,sources)) {
            guard.check();store.putBlob("articles",base+"result.json",Json.write(Map.of("model",ai.editorModel(),"policy",POLICY,"accepted",false,"reason","Original institution perspective omitted")));
            return aligned;
        }
        proposal=SourceEvidence.align(proposal,sources);
        var result=Validation.grounded(proposal,sources,new Verification(true,false,List.of()));
        guard.check();store.putBlob("articles",base+"result.json",Json.write(Map.of("model",ai.editorModel(),"policy",POLICY,"accepted",result.passed(),"issues",result.issues())));
        return result.passed()?proposal:aligned;
    }
    static boolean preservesPerspectives(Draft original,Draft revised,List<Release> sources) {
        var institutions=new HashMap<String,String>();sources.forEach(s->institutions.put(s.id(),ResearchRegistry.group(s.sourceId())));
        var originalGroups=original.claims().stream().map(c->institutions.get(c.sourceId())).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        var revisedGroups=revised.claims().stream().map(c->institutions.get(c.sourceId())).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return revisedGroups.containsAll(originalGroups);
    }
    private DraftRepair() {}
}
