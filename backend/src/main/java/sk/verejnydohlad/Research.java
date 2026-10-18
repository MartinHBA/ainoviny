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
    private static final Set<String> FOCUS_STOP=Set.of("sloven","republ","minist","vlada","vladny","verejn","financ","hospod","sprava","tlacov","inform","uviedl","uvied","podla","ktory","ktora","ktore","tento","tejto","rokoch","roku","dnes","novy","noveho","nova","nove","nbs","rrz","nku","ifp","uhp","pre","pri","ako","nad","pod","len","ich","bol","bola","boli","bude","budu","medzi","svoj","svoje","statny","statne","hodnot","opatren","rozhod");
    private static Set<String> focusTokens(String text) {
        String plain=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return Arrays.stream(plain.split("[^a-z]+")).filter(w->w.length()>=3).map(w->w.substring(0,Math.min(6,w.length()))).filter(w->!FOCUS_STOP.contains(w)).collect(Collectors.toSet());
    }
    /** A lexical lead gate, not a semantic finding: generic institutional boilerplate cannot fill the research pack. */
    static List<Release> targeted(Draft concept,List<Release> seeds,List<String> queries,List<Release> archive) {
        var topic=focusTokens(concept.title()+" "+seeds.stream().map(Release::title).collect(Collectors.joining(" ")));
        var query=focusTokens(String.join(" ",queries));var ids=seeds.stream().map(Release::id).collect(Collectors.toSet());
        var result=new ArrayList<>(seeds);
        archive.stream().filter(r->!ids.contains(r.id())).filter(r->{
            var headline=focusTokens(r.title());var text=focusTokens(r.title()+" "+r.body().substring(0,Math.min(6000,r.body().length())));
            var shared=new HashSet<>(topic);shared.retainAll(text);
            return !shared.isEmpty() && (shared.stream().anyMatch(headline::contains) || shared.stream().anyMatch(query::contains) || shared.size()>=2);
        }).sorted(Comparator.<Release>comparingInt(r->{var title=focusTokens(r.title());return (int)topic.stream().filter(title::contains).count();}).reversed()
            .thenComparing(Release::publishedAt,Comparator.reverseOrder()).thenComparing(Release::id)).limit(Math.max(0,6-seeds.size())).forEach(result::add);
        return List.copyOf(result);
    }
    static List<Release> writingSources(List<Release> seeds,List<Release> retrieved,ResearchBrief brief) {
        var retained=seeds.stream().map(Release::id).collect(Collectors.toSet());brief.findings().forEach(f->retained.add(f.sourceId()));
        var available=retrieved.stream().map(Release::id).collect(Collectors.toSet());
        if(!available.containsAll(retained))throw new IllegalArgumentException("Missing accepted research source");
        return retrieved.stream().filter(r->retained.contains(r.id())).toList();
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
            if(!EvidenceNumbers.missing(finding.text(),evidence).isEmpty())throw new IllegalArgumentException("Ungrounded research number");
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
