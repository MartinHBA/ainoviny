package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;
import sk.verejnydohlad.Models.*;

/** A bounded editorial desk: discovery, commissions, private reserve and morning publication. */
final class Newsroom {
    static final String POLICY="newsroom-v1", REVISION="editorial-revision-v1";
    static final String PUBLICATION_POLICY="cited-originals-v2";
    private static final Logger LOG=Logger.getLogger(Newsroom.class.getName());
    interface Discovery {
        record Result(int documents,int checked,List<String> gaps){}
        Result scan(Store store,Store.Guard guard,Instant deadline);
    }
    record PublicationAudit(String hash,Instant checkedAt,List<Release> sources,Verification verification,EditorDecision decision){}
    record Summary(String scope,String mode,RunResult result,int planned,int ready,int held,int publicationShortfall,int calls,int concepts,List<String> blockers,List<String> discoveryGaps){}
    final Store store;final Ai ai;final Config config;final Clock clock;final ResearchLookup lookup;final Discovery discovery;final PublicationCheck freshness;
    private Map<String,StoryOrder> knownOrders=new HashMap<>();
    private int mergedDrafts,mergePlanned;
    private final Set<String> attemptedMerges=new HashSet<>();
    Newsroom(Store store,Ai ai,Config config,Clock clock,ResearchLookup lookup) {
        this(store,ai,config,clock,lookup,config.aiMode().equals("azure")?new OfficialDiscovery():(s,g,d)->new Discovery.Result(0,0,List.of()),
            config.aiMode().equals("azure")?PublicationCheck.official():PublicationCheck.offline());
    }
    Newsroom(Store store,Ai ai,Config config,Clock clock,ResearchLookup lookup,Discovery discovery,PublicationCheck freshness) {
        this.store=store;this.ai=ai;this.config=config;this.clock=clock;this.lookup=lookup;this.discovery=discovery;this.freshness=freshness;
    }
    static StoryOrder order(Store store,String id){String raw=store.operational("story|"+id);return raw==null?null:Json.read(raw,StoryOrder.class);}
    private StoryOrder commissioned(String id){return knownOrders.get(id);}
    static String revisionId(String parent){return Normalize.hash(parent+"|"+REVISION);}
    static String storyId(StoryPitch pitch){return Normalize.hash(pitch.primaryId()+"|"+POLICY);}
    private boolean valid(Article a) {
        var order=commissioned(a.id());
        return order!=null?clock.instant().isBefore(order.expiresAt()):a.generatedAt()!=null && a.generatedAt().isAfter(clock.instant().minus(Duration.ofDays(2)));
    }
    private int ready(){return (int)store.editorial().stream().filter(a->a.status()==Status.REVIEWED && AutomaticEditor.isAutomatic(a) && valid(a) && !EditionDesk.routed(store,a.id()) && store.operational("reserve-hold|"+a.id())==null).count();}
    private boolean needed(boolean prepare,RunScope scope){return prepare?ready()<config.readyTarget():!new Editorial(store,clock,config.maxPublished(),scope).publicationLimitReached();}
    private <T>T paid(Supplier<T> action,RunScope scope,Store.Guard guard,Instant deadline) {
        guard.check();if(Instant.now().plusSeconds(config.aiTimeout()+15).isAfter(deadline))throw new IllegalStateException("Newsroom time budget reached");
        if(!store.reserveCall(scope.budgetKey(clock),config.maxCalls()))throw new IllegalStateException("Execution AI call budget reached");return action.get();
    }
    RunResult run(boolean collect,RunScope scope,boolean prepare) {
        Instant started=clock.instant();var errors=new ArrayList<String>();var blockers=new ArrayList<String>();var gaps=new ArrayList<String>();
        var attemptedPublications=new HashSet<String>();
        int collected=0,planned=0,drafts=0,published=0,rejected=0;
        mergedDrafts=0;mergePlanned=0;attemptedMerges.clear();
        try(var guard=store.lock()) {
            knownOrders=new HashMap<>();store.orders().forEach(o->knownOrders.put(o.articleId(),o));
            // Finish parent routing from an interrupted merge before any reserve article can publish.
            for(var order:knownOrders.values()) {
                var child=store.article(order.articleId());
                if(store.operational("merge|"+order.articleId())!=null && (child==null || child.value().status()==Status.DRAFT && !child.value().phase().equals("COMPLETE")))
                    EditionDesk.input(store,order.articleId(),ai,guard);
            }
            Instant deadline=Instant.now().plusSeconds(480);
            if(!prepare && config.autoPublish())published+=publishReserve(scope,guard,deadline,blockers,attemptedPublications,errors);
            if(collect && needed(prepare,scope)) {
                collected+=new Collector().collect(SourceRegistry.load(),store,config.maxReleases(),guard,Instant.now().plusSeconds(90),errors);
                var found=discovery.scan(store,guard,deadline);collected+=found.documents();gaps.addAll(found.gaps());
            }
            for(var a:store.editorial()) {
                if(!needed(prepare,scope) || Instant.now().plusSeconds(65).isAfter(deadline))break;
                if(commissioned(a.id())!=null || a.status()!=Status.DRAFT || a.phase().equals("COMPLETE"))continue;
                guard.check();if(!store.reserveArticle(scope.budgetKey(clock),a.id(),config.maxArticles()))continue;
                try{if(process(a,scope,guard,deadline))drafts++;}catch(Exception e){errors.add("legacy resume "+a.id()+": "+Pipeline.errorDescription(e));}
            }
            // Resume exact commissions/checkpoints before buying new work. Orders survive a create crash.
            for(var order:knownOrders.values().stream().sorted(Comparator.comparing(StoryOrder::plannedAt)).toList()) {
                if(!needed(prepare,scope) || Instant.now().plusSeconds(65).isAfter(deadline))break;
                if(!clock.instant().isBefore(order.expiresAt()))continue;
                var version=store.article(order.articleId());
                if(version==null) {if(!reserve(order,scope,guard))continue;version=store.article(order.articleId());}
                if(version.value().status()!=Status.DRAFT)continue;
                if(store.operational("merge|"+order.articleId())!=null && !attemptedMerges.add(order.articleId()))continue;
                if(!version.value().phase().equals("COMPLETE")) {
                    try {if(process(version.value(),scope,guard,deadline))drafts++;}
                    catch(Exception e){errors.add("resume "+order.articleId()+": "+Pipeline.errorDescription(e));}
                }
            }
            // Also adopt already completed legacy drafts; published/rejected originals remain untouched.
            if(config.autoPublish())rejected+=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors,false).rejected();
            if(!prepare && config.autoPublish())published+=publishReserve(scope,guard,deadline,blockers,attemptedPublications,errors);
            if(needed(prepare,scope) && room(scope) && Instant.now().plusSeconds(90).isBefore(deadline)) {
                var candidates=candidates();
                if(candidates.isEmpty())blockers.add("No unused eligible document for a new commission.");
                else {
                    var history=store.editorial();String inputHash=Normalize.hash(Json.write(List.of(candidates,history.stream().map(a->List.of(a.id(),a.title(),a.status().name())).toList(),ai.editorModel(),POLICY)));
                    String path="newsroom-plans/"+inputHash+".json";String raw=store.optionalBlob("articles",path);
                    List<StoryPitch> pitches;
                    if(raw==null) {
                        pitches=paid(()->ai.commission(candidates,history),scope,guard,deadline);validate(pitches,candidates);
                        guard.check();store.putBlob("articles",path,Json.write(pitches));
                    } else {pitches=Arrays.asList(Json.read(raw,StoryPitch[].class));validate(pitches,candidates);}
                    for(var pitch:diverse(pitches)) {
                        if(!prepare && ready()>=config.maxPublished()-new Editorial(store,clock,config.maxPublished(),scope).publishedCount())break;
                        if(store.budget(scope.budgetKey(clock)).articleIds().size()>=Math.max(1,config.maxArticles()-1))break;
                        if(pitch.score()<config.minScore() || !needed(prepare,scope) || !room(scope) || Instant.now().plusSeconds(65).isAfter(deadline))continue;
                        if(pitch.kind().equals("NEWS") && store.release(pitch.primaryId()).publishedAt().isBefore(clock.instant().minus(Duration.ofDays(7))))continue;
                        String id=storyId(pitch);if(store.article(id)!=null)continue;
                        Instant expires=clock.instant().plus(Duration.ofDays(pitch.kind().equals("NEWS")?2:7));
                        var order=new StoryOrder(id,pitch,null,clock.instant(),expires,List.of());
                        if(!reserve(order,scope,guard))break;planned++;
                        try{if(process(store.article(id).value(),scope,guard,deadline))drafts++;}
                        catch(Exception e){errors.add("commission "+id+": "+Pipeline.errorDescription(e));}
                        if(config.autoPublish())rejected+=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors,false).rejected();
                    }
                }
            }
            if(!prepare && config.autoPublish())published+=publishReserve(scope,guard,deadline,blockers,attemptedPublications,errors);
            // Two bounded returns per run at most; no successor may itself start a revision chain.
            int returns=0,triaged=0;
            for(var parent:store.editorial().stream().filter(a->a.status()==Status.REJECTED).sorted(Comparator.comparing(Article::createdAt).reversed()).toList()) {
                if(returns>=2 || triaged>=2 || !needed(prepare,scope) || !room(scope) || Instant.now().plusSeconds(90).isAfter(deadline))break;
                if(parent.sensitive() || parent.createdAt().isBefore(clock.instant().minus(Duration.ofDays(30))))continue;
                if(EditionDesk.routed(store,parent.id()) || store.operational("merge|"+parent.id())!=null)continue;
                var oldOrder=commissioned(parent.id());if(oldOrder!=null && oldOrder.parentId()!=null)continue;
                if(parent.id().equals(SourceInputs.correctionId(SourceInputs.topicId(SourceInputs.seeds(parent)))))continue;
                String id=revisionId(parent.id());if(store.article(id)!=null)continue;
                String feedbackRaw=store.operational("feedback|"+parent.id());
                if(feedbackRaw!=null && !Json.read(feedbackRaw,RevisionFeedback.class).action().equals("REVISE"))continue;
                var sources=parent.sourceIds().stream().map(store::release).toList();
                try {
                    var draft=new Draft(parent.title(),parent.perex(),parent.category(),store.blob("articles",parent.blobPath()),parent.claims());
                    var feedback=feedbackRaw==null?paid(()->ai.feedback(draft,sources,parent.issues()),scope,guard,deadline):Json.read(feedbackRaw,RevisionFeedback.class);validate(feedback);
                    guard.check();if(feedbackRaw==null){store.operational("feedback|"+parent.id(),Json.write(feedback));triaged++;}
                    if(!feedback.action().equals("REVISE"))continue;
                    String primary=oldOrder==null?SourceInputs.seeds(parent).getFirst():oldOrder.pitch().primaryId();
                    var pitch=oldOrder==null?new StoryPitch(primary,parent.sourceIds(),"Presne a vecne prepracovať: "+parent.title(),"ANALYSIS",75,"One editor-directed revision"):oldOrder.pitch();
                    var instructions=new ArrayList<>(parent.issues());instructions.addAll(feedback.instructions());
                    var order=new StoryOrder(id,pitch,parent.id(),clock.instant(),clock.instant().plus(Duration.ofDays(7)),List.copyOf(instructions));
                    if(!reserve(order,scope,guard))break;returns++;planned++;
                    if(process(store.article(id).value(),scope,guard,deadline))drafts++;
                    if(config.autoPublish())rejected+=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors,false).rejected();
                }catch(Exception e){errors.add("revision "+parent.id()+": "+Pipeline.errorDescription(e));}
            }
            if(config.autoPublish()) {
                if(prepare)consolidate(scope,guard,deadline,blockers,errors);
                else published+=publishReserve(scope,guard,deadline,blockers,attemptedPublications,errors);
            }
            var budget=store.budget(scope.budgetKey(clock));
            if(budget.calls()>=config.maxCalls())blockers.add("AI call budget reached.");
            if(budget.articleIds().size()>=config.maxArticles())blockers.add("Concept attempt budget reached.");
            if(!prepare && needed(false,scope))blockers.add("Not enough approved current articles for the edition; quality gates were retained.");
        }catch(Exception e){errors.add("newsroom: "+Pipeline.errorDescription(e));}
        var result=new RunResult(started,clock.instant(),collected,drafts+mergedDrafts,published,rejected,List.copyOf(errors));
        var budget=store.budget(scope.budgetKey(clock));int held=(int)store.editorial().stream().filter(a->a.status()!=Status.PUBLISHED &&
            (a.status()==Status.REVIEWED && !valid(a) || store.operational("reserve-hold|"+a.id())!=null || Optional.ofNullable(store.operational("feedback|"+a.id())).map(v->Json.read(v,RevisionFeedback.class).action().equals("HOLD")).orElse(false))).count();
        var summary=new Summary(scope.label(),prepare?"PREPARE":"EDITION",result,planned+mergePlanned,ready(),held,prepare?0:(int)Math.max(0,config.maxPublished()-new Editorial(store,clock,config.maxPublished(),scope).publishedCount()),budget.calls(),budget.articleIds().size(),blockers.stream().distinct().toList(),List.copyOf(gaps));
        store.run(result,scope.label());store.operational("newsroom-run|"+started+"|"+UUID.randomUUID(),Json.write(summary));LOG.info(Json.write(summary));return result;
    }
    private boolean room(RunScope scope){var b=store.budget(scope.budgetKey(clock));return b.calls()<config.maxCalls() && b.articleIds().size()<config.maxArticles();}
    private boolean reserve(StoryOrder order,RunScope scope,Store.Guard guard) {
        guard.check();if(!store.reserveArticle(scope.budgetKey(clock),order.articleId(),config.maxArticles()))return false;
        String key="story|"+order.articleId();String old=store.operational(key);
        if(old==null)store.operational(key,Json.write(order));else if(!Json.read(old,StoryOrder.class).equals(order))throw new IllegalStateException("Commission changed");
        knownOrders.put(order.articleId(),order);
        var sources=order.pitch().sourceIds().stream().map(store::release).toList();
        guard.check();store.createArticle(new Article(order.articleId(),"sprava-"+order.articleId().substring(0,16),"Pripravuje sa článok","","Slovensko",order.plannedAt(),null,null,Status.DRAFT,"",
            sources.stream().map(Release::id).toList(),sources.stream().map(Release::originalUrl).toList(),order.parentId()==null || ai.model().startsWith("mock")?ai.model():ai.editorModel(),null,null,null,"",false,false,List.of(),List.of(),
            store.operational("merge|"+order.articleId())!=null?"MERGING":order.parentId()==null?"DRAFTING":"REVISING",store.budget(scope.budgetKey(clock)).articleIds().indexOf(order.articleId()),RunScope.date(clock)));
        return true;
    }
    private boolean process(Article article,RunScope scope,Store.Guard guard,Instant deadline) {
        return new Pipeline(store,ai,config,clock,lookup,scope).process(article,article.sourceIds().stream().map(store::release).toList(),guard,deadline);
    }
    private List<Release> candidates() {
        var used=new HashSet<String>();knownOrders.values().forEach(o->used.add(o.pitch().primaryId()));
        for(var a:store.editorial())used.addAll(commissioned(a.id())==null?SourceInputs.seeds(a):List.of(commissioned(a.id()).pitch().primaryId()));
        return store.recent(clock.instant().minus(Duration.ofDays(90)),250).stream().filter(r->!used.contains(r.id()) && !"IGNORED".equals(store.releaseStatus(r)))
            .sorted(Comparator.<Release,Instant>comparing(r->r.publishedAt().equals(Instant.EPOCH)?r.collectedAt():r.publishedAt()).reversed()).limit(24).toList();
    }
    static void validate(List<StoryPitch> pitches,List<Release> candidates) {
        if(pitches==null || pitches.size()>6)throw new IllegalArgumentException("Invalid commissions");
        var ids=candidates.stream().map(Release::id).collect(java.util.stream.Collectors.toSet());var primaries=new HashSet<String>();
        for(var p:pitches)if(p==null || !ids.contains(p.primaryId()) || !primaries.add(p.primaryId()) || p.sourceIds()==null || p.sourceIds().isEmpty() || p.sourceIds().size()>3 ||
            !p.sourceIds().contains(p.primaryId()) || p.sourceIds().stream().distinct().count()!=p.sourceIds().size() || !ids.containsAll(p.sourceIds()) ||
            !Set.of("NEWS","ANALYSIS","EXPLAINER").contains(p.kind()) || p.score()<0 || p.score()>100 || p.question()==null || p.question().length()<10 || p.question().length()>400 ||
            p.reason()==null || p.reason().isBlank() || p.reason().length()>700)throw new IllegalArgumentException("Invalid commission");
    }
    static void validate(RevisionFeedback f) {
        if(f==null || !Set.of("REVISE","HOLD","DROP").contains(f.action()) || f.instructions()==null || f.instructions().size()>8 ||
            f.instructions().stream().anyMatch(s->s==null || s.isBlank() || s.length()>700) || f.reason()==null || f.reason().isBlank() || f.reason().length()>1500 ||
            f.action().equals("REVISE") && f.instructions().isEmpty())throw new IllegalArgumentException("Invalid editorial feedback");
    }
    static List<StoryPitch> diverse(List<StoryPitch> input) {
        var remaining=new ArrayList<>(input.stream().sorted(Comparator.comparingInt(StoryPitch::score).reversed()).toList());var result=new ArrayList<StoryPitch>();var kinds=new HashSet<String>();
        while(!remaining.isEmpty()){var next=remaining.stream().filter(p->!kinds.contains(p.kind())).findFirst().orElse(remaining.getFirst());remaining.remove(next);result.add(next);kinds.add(next.kind());}
        return result;
    }
    private int publishReserve(RunScope scope,Store.Guard guard,Instant deadline,List<String> blockers,Set<String> attempted,List<String> errors) {
        if(!needed(false,scope))return 0;
        consolidate(scope,guard,deadline,blockers,errors);
        int count=0;var candidates=store.editorial().stream().filter(a->a.status()==Status.REVIEWED && AutomaticEditor.isAutomatic(a) && !EditionDesk.routed(store,a.id())).toList();
        var byId=new HashMap<String,Article>();var pitches=new ArrayList<StoryPitch>();
        for(var a:candidates) {
            if(!valid(a)){blockers.add("Expired reserve article: "+a.id());continue;}
            var order=commissioned(a.id());var pitch=order==null?new StoryPitch(a.id(),List.of(a.id()),a.title(),"NEWS",70,"Legacy approval"):new StoryPitch(a.id(),List.of(a.id()),order.pitch().question(),order.pitch().kind(),order.pitch().score(),order.pitch().reason());
            pitches.add(pitch);byId.put(a.id(),a);
        }
        for(var pitch:diverse(pitches)) {
            if(!needed(false,scope) || Instant.now().plusSeconds(65).isAfter(deadline))break;
            var a=byId.get(pitch.primaryId());
            if(!attempted.add(a.id()))continue;
            try {
                guard.check();var draft=new Draft(a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims());
                var cited=draft.claims().stream().map(Claim::sourceId).collect(java.util.stream.Collectors.toSet());
                if(cited.isEmpty() || !new HashSet<>(a.sourceIds()).containsAll(cited))throw new IllegalStateException("Missing cited original");
                // Research snapshots retain all consulted material. Only originals actually cited by this
                // approved text are publication dependencies; every citation still receives a fresh check.
                var sources=a.sourceIds().stream().distinct().filter(cited::contains).map(store::release).toList();
                String base="publication-checks/"+a.id()+"/"+a.approvedHash()+"/"+PUBLICATION_POLICY+"/"+(clock.instant().getEpochSecond()/900)+"/";
                String cached=store.optionalBlob("articles",base+"sources.json");
                var current=cached==null?freshness.refresh(sources,guard,deadline):Arrays.asList(Json.read(cached,Release[].class));
                if(current.size()!=sources.size() || current.stream().mapToLong(r->r.body().length()).sum()>200000 ||
                    !current.stream().map(Release::id).toList().equals(sources.stream().map(Release::id).toList()))throw new IllegalStateException("Invalid current originals");
                guard.check();if(cached==null)store.putBlob("articles",base+"sources.json",Json.write(current));
                String hash=Normalize.hash(Json.write(List.of(a.approvedHash(),current,ai.model(),ai.editorModel(),POLICY,PUBLICATION_POLICY)));
                String raw=store.optionalBlob("articles",base+"verified.json");
                Verification verification=raw==null?Validation.grounded(draft,current,paid(()->ai.verify(draft,current),scope,guard,deadline)):Json.read(raw,Verification.class);
                guard.check();if(raw==null)store.putBlob("articles",base+"verified.json",Json.write(verification));
                if(!verification.passed() || verification.sensitive()) {hold(a,"Current source check did not approve",guard,blockers);continue;}
                String reviewed=store.optionalBlob("articles",base+"approval.json");PublicationAudit audit;
                if(reviewed==null) {
                    var decision=paid(()->ai.review(draft,current),scope,guard,deadline);Validation.editorDecision(decision);
                    audit=new PublicationAudit(hash,clock.instant(),current,verification,decision);guard.check();store.putBlob("articles",base+"approval.json",Json.write(audit));
                } else audit=Json.read(reviewed,PublicationAudit.class);
                Validation.editorDecision(audit.decision());
                if(!audit.hash().equals(hash) || !audit.decision().approved() || audit.decision().sensitive()){hold(a,"Fresh final editor did not approve",guard,blockers);continue;}
                new Editorial(store,clock,config.maxPublished(),scope).publish(a.id(),guard);count++;
            }catch(Exception e){hold(a,"Reserve publication deferred ("+Pipeline.errorDescription(e)+")",guard,blockers);}
        }
        return count;
    }
    private void consolidate(RunScope scope,Store.Guard guard,Instant deadline,List<String> blockers,List<String> errors) {
        var children=EditionDesk.prepare(store,ai,config,clock,scope.budgetKey(clock),guard,new EditionDesk.Paid(){
            public <T>T call(Supplier<T> action){return paid(action,scope,guard,deadline);}
        },blockers);
        for(String id:children) {
            var order=order(store,id);knownOrders.put(id,order);
            boolean created=store.article(id)==null;
            if(!reserve(order,scope,guard))continue;
            if(created)mergePlanned++;
            if(!attemptedMerges.add(id))continue;
            try{if(process(store.article(id).value(),scope,guard,deadline))mergedDrafts++;}
            catch(Exception e){errors.add("merge "+id+": "+Pipeline.errorDescription(e));}
        }
        if(!children.isEmpty())new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors,false);
    }
    private void hold(Article article,String reason,Store.Guard guard,List<String> blockers){
        guard.check();store.operational("reserve-hold|"+article.id(),Json.write(Map.of("reason",reason,"at",clock.instant())));blockers.add(reason+": "+article.id());
    }
}
