package sk.verejnydohlad;

import java.util.*;
import java.util.stream.Collectors;
import sk.verejnydohlad.Models.*;

/** Bounded document retrieval. Related documents are leads, never corroboration by themselves. */
final class Research {
    static final String POLICY_VERSION="document-analyst-v3";
    record Audit(String inputHash,String model,String policyVersion,ResearchBrief brief) {}
    record FailedAttempt(String inputHash,String model,String error,ResearchBrief brief) {}
    private static final Set<String> STOP=Set.of("sloven","republ","minist","vlada","podla","ktory","ktora","ktore","ktoreh","tento","tejto","svojic","verejn","sprava","tlacov","narodn","rokoch","oktobr","septem","novemb","decemb","januar","februa","dnes","noveho");
    static List<Release> related(List<Release> seeds,List<Release> archive) {
        var result=new ArrayList<>(seeds);var ids=seeds.stream().map(Release::id).collect(Collectors.toSet());
        var tokens=seeds.stream().flatMap(s -> tokens(s.title()+" "+s.body().substring(0,Math.min(1200,s.body().length()))).stream()).collect(Collectors.toSet());
        archive.stream().filter(r -> !ids.contains(r.id()))
            .filter(r -> seeds.stream().anyMatch(s -> !s.sourceId().equals(r.sourceId())))
            .filter(r -> overlap(tokens,r)>=2)
            .sorted(Comparator.<Release>comparingInt(r -> overlap(tokens,r)).reversed().thenComparing(Release::publishedAt,Comparator.reverseOrder()).thenComparing(Release::id))
            .limit(Math.max(0,6-seeds.size())).forEach(result::add);
        return List.copyOf(result);
    }
    private static int overlap(Set<String> terms,Release r) {
        return (int)tokens(r.title()+" "+r.body().substring(0,Math.min(1200,r.body().length()))).stream().filter(terms::contains).count();
    }
    private static Set<String> tokens(String text) {
        String normalized=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return Arrays.stream(normalized.split("[^a-z]+"))
            .filter(t -> t.length()>=5).map(t -> t.substring(0,Math.min(6,t.length()))).filter(t -> !STOP.contains(t)).collect(Collectors.toSet());
    }
    static boolean multiSource(List<Release> sources) { return sources.stream().map(s -> ResearchRegistry.group(s.sourceId())).distinct().count()>=2; }
    static void validate(ResearchBrief brief,List<Release> sources) {
        if(brief==null || brief.question()==null || brief.question().length()>600 || brief.findings()==null || brief.findings().size()>8 ||
            brief.limitations()==null || brief.leads()==null || brief.limitations().size()>8 || brief.leads().size()>8 ||
            java.util.stream.Stream.concat(brief.limitations().stream(),brief.leads().stream()).anyMatch(s -> s==null || s.length()>700))
            throw new IllegalArgumentException("Invalid research brief");
        var byId=sources.stream().collect(Collectors.toMap(Release::id,s -> s));
        for(var finding:brief.findings()) {
            if(finding==null || finding.text()==null || finding.text().length()<5 || finding.text().length()>1200 || !byId.containsKey(finding.sourceId()))
                throw new IllegalArgumentException("Invalid research finding");
            String evidence=SourceEvidence.allExcerpts(byId.get(finding.sourceId())).stream().filter(e -> e.id().equals(finding.evidenceId())).map(SourceEvidence.Excerpt::text).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown research evidence identifier"));
            var numbers=java.util.regex.Pattern.compile("\\d+(?:[.,]\\d+)*");var originals=new HashSet<String>();var matcher=numbers.matcher(evidence);
            while(matcher.find()) originals.add(matcher.group().replace(',','.'));
            matcher=numbers.matcher(finding.text());while(matcher.find()) if(!originals.contains(matcher.group().replace(',','.'))) throw new IllegalArgumentException("Ungrounded research number");
        }
        if(brief.viable() && (!multiSource(sources) || brief.findings().stream().map(f -> ResearchRegistry.group(byId.get(f.sourceId()).sourceId())).distinct().count()<2 || brief.question().isBlank()))
            throw new IllegalArgumentException("Analytical brief needs evidence from different institutions");
    }
    static void coverage(Draft draft,ResearchBrief brief,List<Release> sources) {
        if(brief!=null && brief.viable()) {
            var byId=sources.stream().collect(Collectors.toMap(Release::id,s -> ResearchRegistry.group(s.sourceId())));
            if(draft.claims().stream().map(c -> byId.get(c.sourceId())).filter(Objects::nonNull).distinct().count()<2)
                throw new IllegalArgumentException("Analytical draft omitted its second source");
        }
    }
    static ResearchBrief discardUnsupportedNumbers(ResearchBrief brief,List<Release> sources) {
        var retained=new ArrayList<GenerationClaim>();
        for(var finding:brief.findings()) {
            try {
                validate(new ResearchBrief(false,brief.sensitive(),brief.question(),List.of(finding),brief.limitations(),brief.leads()),sources);
                retained.add(finding);
            } catch(IllegalArgumentException invalid) {
                if(!"Ungrounded research number".equals(invalid.getMessage())) throw invalid;
            }
        }
        var institutions=sources.stream().collect(Collectors.toMap(Release::id,s -> ResearchRegistry.group(s.sourceId())));
        boolean viable=brief.viable() && retained.stream().map(f -> institutions.get(f.sourceId())).distinct().count()>=2;
        var cleaned=new ResearchBrief(viable,brief.sensitive(),brief.question(),List.copyOf(retained),brief.limitations(),brief.leads());
        validate(cleaned,sources);return cleaned;
    }
    private Research() {}
}
