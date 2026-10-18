package sk.verejnydohlad;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import sk.verejnydohlad.Models.*;

/** One initial concept, one bounded research pass, one rewrite. Every completed step is immutable. */
final class ResearchFlow {
    static final String POLICY="writer-assistant-v2-relevant-sources";
    interface Paid {<T> T call(Supplier<T> action);}
    record Snapshot(String inputHash,String policy,List<Release> documents,List<String> gaps) {}
    record Outcome(Draft draft,List<Release> sources,ResearchBrief brief) {}
    static Outcome run(Article article,List<Release> seeds,Store store,Ai ai,ResearchLookup lookup,Store.Guard guard,Instant deadline,Paid paid) {
        String inputHash=Normalize.hash(Json.write(List.of(seeds,ai.model(),ai.editorModel(),POLICY,ResearchRegistry.SITES)));
        String root="assistant/"+article.id()+"/"+inputHash+"/";
        String raw=store.optionalBlob("articles",root+"initial.json");
        var order=Newsroom.order(store,article.id());
        Draft initial=raw==null?paid.call(() -> order==null?ai.generate(seeds):ai.write(order.pitch(),seeds)):Json.read(raw,Draft.class);Validation.draft(initial);
        save(store,guard,root+"initial.json",initial);
        String planRaw=store.optionalBlob("articles",root+"plan.json");
        ResearchPlan plan=planRaw==null?paid.call(() -> ai.plan(initial,seeds)):Json.read(planRaw,ResearchPlan.class);
        ResearchRegistry.validate(plan);
        // A state-budget concept must at least attempt its independent fiscal assessment, even if the
        // planning model omits it. This is source selection, never a forced conclusion or approval.
        if(OfficialResearch.score("rozpočet",initial.title()+" "+initial.perex())>0 && !plan.sourceIds().contains("rrz-sk")) {
            var ids=new ArrayList<String>();ids.add("rrz-sk");plan.sourceIds().stream().limit(3).forEach(ids::add);
            plan=new ResearchPlan(plan.question(),plan.queries(),List.copyOf(ids));
        }
        save(store,guard,root+"plan.json",plan);
        String snapshotRaw=store.optionalBlob("articles",root+"retrieval.json");Snapshot snapshot;
        if(snapshotRaw==null) {
            var result=lookup.find(plan,initial,seeds,store,guard,deadline);
            snapshot=new Snapshot(inputHash,POLICY,result.documents(),result.gaps());validate(snapshot,inputHash,seeds);
            save(store,guard,root+"retrieval.json",snapshot);
        } else {snapshot=Json.read(snapshotRaw,Snapshot.class);validate(snapshot,inputHash,seeds);}
        List<Release> sources=snapshot.documents();
        String briefRaw=store.optionalBlob("articles",root+"brief.json");ResearchBrief brief;
        if(briefRaw==null) {
            brief=paid.call(() -> ai.research(initial,sources,snapshot.gaps()));
            try {Research.validate(brief,sources);}
            catch(IllegalArgumentException invalid) {
                save(store,guard,root+"invalid-brief-"+UUID.randomUUID()+".json",new Research.FailedAttempt(inputHash,ai.editorModel(),invalid.getClass().getSimpleName(),brief));
                if(!"Ungrounded research number".equals(invalid.getMessage()))throw invalid;
                brief=Research.discardUnsupportedNumbers(brief,sources);
            }
            save(store,guard,root+"brief.json",new Research.Audit(inputHash,ai.editorModel(),POLICY,brief));
        } else {
            var audit=Json.read(briefRaw,Research.Audit.class);
            if(!inputHash.equals(audit.inputHash()) || !POLICY.equals(audit.policyVersion()) || !ai.editorModel().equals(audit.model()))throw new IllegalStateException("Invalid assistant research audit");
            brief=audit.brief();Research.validate(brief,sources);
        }
        ResearchBrief accepted=brief;
        // The analyst reads all retrieval leads, but only the commissioned sources and originals
        // supporting accepted findings reach the writer. The full retrieval stays in the private audit.
        var writingSources=Research.writingSources(seeds,sources,brief);
        String rewritten=store.optionalBlob("articles",root+"rewritten.json");
        Draft draft=rewritten==null?paid.call(() -> ai.rewrite(initial,writingSources,accepted)):Json.read(rewritten,Draft.class);
        Validation.draft(draft);Research.coverage(draft,brief,writingSources);
        var institutions=writingSources.stream().collect(java.util.stream.Collectors.toMap(Release::id,s -> ResearchRegistry.group(s.sourceId())));
        if(draft.claims().stream().anyMatch(c->!institutions.containsKey(c.sourceId())))throw new IllegalArgumentException("Draft cited unaccepted research source");
        var covered=draft.claims().stream().map(c -> institutions.get(c.sourceId())).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if(brief.findings().stream().map(f -> institutions.get(f.sourceId())).anyMatch(group -> !covered.contains(group)))
            throw new IllegalArgumentException("Researched draft omitted a relevant institution");
        save(store,guard,root+"rewritten.json",draft);
        return new Outcome(draft,writingSources,brief);
    }
    private static void validate(Snapshot s,String hash,List<Release> seeds) {
        if(!hash.equals(s.inputHash()) || !POLICY.equals(s.policy()) || s.documents()==null || s.documents().isEmpty() || s.documents().size()>6 ||
            s.documents().stream().map(Release::id).distinct().count()!=s.documents().size() || s.gaps()==null || s.gaps().size()>16 ||
            s.gaps().stream().anyMatch(g -> g==null || g.length()>1000) || s.documents().stream().mapToLong(r -> r.body().length()).sum()>200000 ||
            seeds.stream().anyMatch(seed -> s.documents().stream().noneMatch(r -> r.equals(seed))))throw new IllegalStateException("Invalid assistant retrieval snapshot");
        for(var r:s.documents()) {
            if(!Normalize.hash(r.originalUrl()).equals(r.id()) || !Normalize.hash(r.body()).equals(r.contentHash()) || r.body().length()>100000)
                throw new IllegalStateException("Invalid assistant source hash");
        }
    }
    private static void save(Store store,Store.Guard guard,String path,Object value) {guard.check();store.putBlob("articles",path,Json.write(value));}
    private ResearchFlow() {}
}
