package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

/** Stable contiguous originals. Models select an ID; they never recopy the evidence used for approval. */
final class SourceEvidence {
    record Excerpt(String id,String text) {}
    static List<Excerpt> excerpts(Release source) {
        return excerpts(source,10000);
    }
    static List<Excerpt> allExcerpts(Release source) {return excerpts(source,100000);}
    static List<Excerpt> researchExcerpts(Release source,String question) {
        var all=allExcerpts(source);var selected=new LinkedHashSet<Excerpt>();
        all.stream().limit(3).forEach(selected::add);
        all.stream().sorted(Comparator.<Excerpt>comparingInt(e -> overlap(question,e.text())).reversed().thenComparing(Excerpt::id)).limit(9).forEach(selected::add);
        return List.copyOf(selected);
    }
    private static List<Excerpt> excerpts(Release source,int max) {
        var result=new ArrayList<Excerpt>();
        if(source.title().length()>=10 && source.title().length()<=1800) result.add(new Excerpt("title",source.title()));
        String body=source.body().substring(0,Math.min(max,source.body().length()));
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
            if(!supportsNumbers(claim.text(),evidence)) {
                // Correct a selection error only within the same supplied document. Never borrow another source,
                // stitch spans or relax a numeric check; semantic verification still checks the resulting citation.
                String replacement=originals.get(claim.sourceId()).values().stream().filter(e -> supportsNumbers(claim.text(),e))
                    .sorted(Comparator.<String>comparingInt(e -> overlap(claim.text(),e)).reversed().thenComparing(Comparator.naturalOrder()))
                    .findFirst().orElse(null);
                if(replacement!=null)evidence=replacement;
            }
            claims.add(new Claim(claim.text(),claim.sourceId(),evidence));
        }
        Draft draft=new Draft(claims.get(0).text(),claims.get(1).text(),generated.category(),
            claims.subList(2,claims.size()).stream().map(Claim::text).collect(java.util.stream.Collectors.joining("\n\n")),List.copyOf(claims));
        Validation.draft(draft);return draft;
    }
    static Draft assemble(ResearchedGeneration generated,List<Release> sources) {
        if(generated==null || generated.title()==null || generated.perex()==null || generated.paragraphs()==null || generated.paragraphs().isEmpty() || generated.paragraphs().size()>8)
            throw new IllegalArgumentException("Incomplete researched generation");
        var byId=new HashMap<String,Map<String,String>>();
        for(var s:sources) {var excerpts=new HashMap<String,String>();allExcerpts(s).forEach(e -> excerpts.put(e.id(),e.text()));byId.put(s.id(),excerpts);}
        var fields=new ArrayList<ResearchedClaim>();fields.add(generated.title());fields.add(generated.perex());fields.addAll(generated.paragraphs());
        var claims=new ArrayList<Claim>();
        for(var field:fields) {
            if(field==null || field.text()==null || field.text().contains("\n") || field.text().contains("\r") || field.citations()==null || field.citations().isEmpty() || field.citations().size()>3 ||
                new HashSet<>(field.citations()).size()!=field.citations().size())throw new IllegalArgumentException("Invalid researched claim");
            for(var ref:field.citations()) {
                if(ref==null)throw new IllegalArgumentException("Invalid researched citation");
                String evidence=byId.getOrDefault(ref.sourceId(),Map.of()).get(ref.evidenceId());
                if(evidence==null)throw new IllegalArgumentException("Unknown source evidence identifier");
                // Persist one existing Claim record per citation, preserving compatibility with stored articles.
                claims.add(new Claim(field.text(),ref.sourceId(),evidence));
            }
        }
        var draft=new Draft(generated.title().text(),generated.perex().text(),generated.category(),
            generated.paragraphs().stream().map(ResearchedClaim::text).collect(java.util.stream.Collectors.joining("\n\n")),List.copyOf(claims));
        Validation.draft(draft);return draft;
    }
    private static boolean supportsNumbers(String text,String evidence) {
        var pattern=java.util.regex.Pattern.compile("\\d+(?:[.,]\\d+)*");var found=new HashSet<String>();var matcher=pattern.matcher(evidence);
        while(matcher.find())found.add(matcher.group().replace(',','.'));matcher=pattern.matcher(text);
        while(matcher.find())if(!found.contains(matcher.group().replace(',','.')))return false;return true;
    }
    private static int overlap(String text,String evidence) {
        var words=new HashSet<>(Arrays.asList(Normalize.text(text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        return (int)Arrays.stream(Normalize.text(evidence).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).distinct().filter(w -> w.length()>3 && words.contains(w)).count();
    }
    private SourceEvidence() {}
}
