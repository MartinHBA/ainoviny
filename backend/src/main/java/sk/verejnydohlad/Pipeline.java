package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import sk.verejnydohlad.Models.*;

public final class Pipeline {
    private final Store store; private final Ai ai; private final Config config; private final Clock clock;
    public Pipeline(Store store,Ai ai,Config config,Clock clock) { this.store=store; this.ai=ai; this.config=config; this.clock=clock; }
    public RunResult run(boolean collect) {
        Instant start=clock.instant(); var errors=new ArrayList<String>(); int collected=0, drafts=0;
        try (Store.Guard guard=store.lock()) {
            Instant deadline=Instant.now().plusSeconds(480);
            if(collect) collected=new Collector().collect(SourceRegistry.load(),store,config.maxReleases(),guard,Instant.now().plusSeconds(120),errors);
            // Repair a crash between saving a completed draft and marking its input material consumed.
            var incomplete=new ArrayList<Article>();
            for(Article a:store.editorial()) {
                if(a.phase().equals("COMPLETE") || a.status()==Status.REJECTED) { for(String id:a.sourceIds()) { guard.check(); store.releaseStatus(store.release(id),"DONE",null); } }
                else incomplete.add(a);
            }
            Set<String> claimed=new HashSet<>();
            for(Article a:incomplete) {
                claimed.addAll(a.sourceIds());
                try { if(process(a,a.sourceIds().stream().map(store::release).toList(),guard,deadline)) drafts++; }
                catch(Exception e) { errors.add("resume "+a.id()+": "+e.getClass().getSimpleName()); }
            }
            List<Release> candidates=store.pending(config.maxReleases()).stream().filter(s -> !claimed.contains(s.id())).toList();
            List<Release> unranked=candidates.stream().filter(s -> store.analysis(s)==null).toList();
            // Batches keep structured-output size and cost bounded. Persist each ranking to avoid repeat calls.
            for(int offset=0;offset<unranked.size();offset+=6) {
                var batch=unranked.subList(offset,Math.min(offset+6,unranked.size()));
                Map<String,Analysis> ranked=paid(() -> ai.analyze(batch),guard,deadline);
                for(Release r:batch) {
                    Analysis analysis=ranked.get(r.id()); Validation.analysis(analysis); guard.check();
                    store.releaseStatus(r,analysis.score()<config.minScore()?"IGNORED":"ANALYZED",analysis);
                }
            }
            Map<String,List<Release>> groups=new TreeMap<>();
            for(Release r:candidates) {
                var a=store.analysis(r);
                if(a!=null && a.score()>=config.minScore()) groups.computeIfAbsent(a.topicKey(),key -> new ArrayList<>()).add(r);
            }
            List<List<Release>> selected=new ArrayList<>(groups.values());
            selected.sort(Comparator.<List<Release>>comparingInt(g -> g.stream().map(store::analysis).mapToInt(Analysis::score).max().orElse(0)).reversed()
                .thenComparing(g -> g.getFirst().id()));
            for(var group:selected) {
                if(Instant.now().isAfter(deadline)) break;
                String id=Normalize.hash(group.stream().map(Release::id).sorted().reduce((a,b) -> a+"|"+b).orElseThrow());
                if(store.article(id)!=null) continue;
                guard.check(); String day=day();
                if(!store.reserveArticle(day,id,config.maxArticles())) break;
                var initial=new Article(id,"sprava-"+id.substring(0,16),"Pripravuje sa", "",store.analysis(group.getFirst()).category(),clock.instant(),null,null,Status.DRAFT,
                    "",group.stream().map(Release::id).toList(),group.stream().map(Release::originalUrl).distinct().toList(),ai.model(),
                    null,null,null,"",false,false,List.of(),List.of(),"GENERATING",store.budget(day).articleIds().indexOf(id),day);
                guard.check(); if(!store.createArticle(initial)) continue;
                try { if(process(initial,group,guard,deadline)) drafts++; }
                catch(Exception e) { errors.add("draft "+id+": "+e.getClass().getSimpleName()); }
            }
        } catch(Exception e) { errors.add("pipeline: "+e.getClass().getSimpleName()); }
        var result=new RunResult(start,clock.instant(),collected,drafts,List.copyOf(errors)); store.run(result); return result;
    }
    private boolean process(Article initial,List<Release> group,Store.Guard guard,Instant deadline) {
        guard.check(); if(!store.reserveArticle(day(),initial.id(),config.maxArticles())) return false;
        var stored=store.article(initial.id()); Article a=stored.value();
        if(a.phase().equals("COMPLETE")) return false;
        Draft draft;
        if(a.phase().equals("GENERATING")) {
            String checkpoint="drafts/"+a.id()+"/generated.json";
            String recovered=store.optionalBlob("articles",checkpoint);
            draft=recovered==null?paid(() -> ai.generate(group),guard,deadline):Json.read(recovered,Draft.class); Validation.draft(draft);
            guard.check(); store.putBlob("articles",checkpoint,Json.write(draft));
            var next=new Article(a.id(),Normalize.slug(draft.title())+"-"+a.id().substring(0,12),draft.title(),draft.perex(),draft.category(),a.createdAt(),clock.instant(),null,Status.DRAFT,
                checkpoint,a.sourceIds(),a.sourceUrls(),a.aiModel(),null,null,null,"",false,false,List.of(),draft.claims(),"VERIFYING",a.editorialOrder(),a.editionDate());
            guard.check(); store.updateArticle(next,stored.version()); stored=store.article(a.id()); a=stored.value();
        } else draft=Json.read(store.blob("articles",a.blobPath()),Draft.class);
        Draft finalDraft=draft;
        Verification verification=Validation.grounded(draft,group,paid(() -> ai.verify(finalDraft,group),guard,deadline));
        String hash=Normalize.hash(draft.markdown()), path="drafts/"+a.id()+"/"+hash+".md";
        guard.check(); store.putBlob("articles",path,draft.markdown());
        guard.check(); store.updateArticle(a.revise(draft,path,hash,verification,clock.instant()),stored.version());
        for(Release r:group) { guard.check(); store.releaseStatus(r,"DONE",null); }
        return true;
    }
    private <T> T paid(Supplier<T> action,Store.Guard guard,Instant deadline) {
        guard.check(); if(Instant.now().plusSeconds(config.aiTimeout()+15).isAfter(deadline)) throw new IllegalStateException("Pipeline time budget reached");
        if(!store.reserveCall(day(),config.maxCalls())) throw new IllegalStateException("Daily AI call budget reached");
        return action.get();
    }
    private String day() { return clock.instant().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString(); }
}
