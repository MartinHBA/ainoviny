package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

/** Stable contiguous originals. Models select an ID; they never recopy the evidence used for approval. */
final class SourceEvidence {
    record Excerpt(String id,String text) {}
    static List<Excerpt> excerpts(Release source) {
        var result=new ArrayList<Excerpt>();
        if(source.title().length()>=10 && source.title().length()<=1800) result.add(new Excerpt("title",source.title()));
        String body=source.body().substring(0,Math.min(10000,source.body().length()));
        for(int start=0,index=0;start<body.length();index++) {
            int end=Math.min(start+1200,body.length());
            if(end<body.length()) {
                int space=body.lastIndexOf(' ',end);
                if(space>start+800) end=space;
            }
            String text=body.substring(start,end).strip();
            if(text.length()>=10) result.add(new Excerpt("body-"+index,text));
            if(end==body.length()) break;
            start=end-200; // Overlap keeps boundary facts available together, without stitching text.
        }
        return List.copyOf(result);
    }
    static Draft assemble(Generation generated,List<Release> sources) {
        if(generated==null || generated.title()==null || generated.perex()==null || generated.paragraphs()==null || generated.paragraphs().isEmpty() || generated.paragraphs().size()>28)
            throw new IllegalArgumentException("Incomplete article generation");
        var originals=new HashMap<String,Map<String,String>>();
        for(var source:sources) {
            var byId=new HashMap<String,String>();excerpts(source).forEach(e -> byId.put(e.id(),e.text()));originals.put(source.id(),byId);
        }
        var input=new ArrayList<GenerationClaim>();input.add(generated.title());input.add(generated.perex());input.addAll(generated.paragraphs());
        var claims=new ArrayList<Claim>();
        for(var claim:input) {
            if(claim==null || claim.text()==null || claim.sourceId()==null || claim.evidenceId()==null || claim.text().contains("\n") || claim.text().contains("\r"))
                throw new IllegalArgumentException("Generation requires single-paragraph text fields");
            String evidence=originals.getOrDefault(claim.sourceId(),Map.of()).get(claim.evidenceId());
            if(evidence==null) throw new IllegalArgumentException("Unknown source evidence identifier");
            claims.add(new Claim(claim.text(),claim.sourceId(),evidence));
        }
        Draft draft=new Draft(claims.get(0).text(),claims.get(1).text(),generated.category(),
            claims.subList(2,claims.size()).stream().map(Claim::text).collect(java.util.stream.Collectors.joining("\n\n")),List.copyOf(claims));
        Validation.draft(draft);return draft;
    }
    private SourceEvidence() {}
}
