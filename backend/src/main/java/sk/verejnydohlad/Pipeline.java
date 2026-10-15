package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import sk.verejnydohlad.Models.*;

public final class Pipeline {
    public record CorrectionInput(String parentId,String inputHash,String model,Instant createdAt) {}
    private static final Logger LOG=Logger.getLogger(Pipeline.class.getName());
    private final Store store; private final Ai ai; private final Config config; private final Clock clock;private final ResearchLookup lookup;private final RunScope scope;
    public Pipeline(Store store,Ai ai,Config config,Clock clock) {this(store,ai,config,clock,config.aiMode().equals("azure")?new OfficialResearch():ResearchLookup.archiveOnly());}
    Pipeline(Store store,Ai ai,Config config,Clock clock,ResearchLookup lookup) {this(store,ai,config,clock,lookup,RunScope.scheduled());}
    Pipeline(Store store,Ai ai,Config config,Clock clock,ResearchLookup lookup,RunScope scope) { this.store=store; this.ai=ai; this.config=config; this.clock=clock;this.lookup=lookup;this.scope=scope; }
    public RunResult runAdHoc(boolean collect){return run(collect,RunScope.adHoc());}
    RunResult run(boolean collect,RunScope scope){return new Pipeline(store,ai,config,clock,lookup,scope).run(collect);}
    public RunResult run(boolean collect) {
        if(config.newsroomFlow())return new Newsroom(store,ai,config,clock,lookup).run(collect,scope,false);
        Instant start=clock.instant(); var errors=new ArrayList<String>(); int collected=0, drafts=0, published=0, rejected=0;
        try (Store.Guard guard=store.lock()) {
            Instant deadline=Instant.now().plusSeconds(480);
            if(collect) collected=new Collector().collect(SourceRegistry.load(),store,config.maxReleases(),guard,Instant.now().plusSeconds(120),errors);
            if(config.researchFlow() && config.autoPublish()) {
                var ready=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors);
                published+=ready.published();rejected+=ready.rejected();
            }
            try {
                var existing=store.editorial();
                var primary=new HashSet<String>();existing.forEach(a -> primary.addAll(SourceInputs.seeds(a)));
                // Earlier releases consumed every context input. Restore only inputs that were never a primary topic.
                var contexts=new HashSet<String>();existing.forEach(a -> contexts.addAll(a.sourceIds()));contexts.removeAll(primary);
                for(String id:contexts) {
                    var source=store.release(id);
                    if(!"DONE".equals(store.releaseStatus(source)))continue;
                    var analysis=store.analysis(source);String state=source.publishedAt().isBefore(clock.instant().minus(Duration.ofDays(7)))?"CONTEXT":
                        analysis==null?"PENDING":analysis.score()<config.minScore()?"IGNORED":"ANALYZED";
                    guard.check();store.releaseStatus(source,state,null);
                }
                for(var parent:existing) {
                    if(!config.autoPublish() || !SourceInputs.correctionCandidate(parent) || store.article(SourceInputs.correctionId(parent.id()))!=null)continue;
                    if(SourceInputs.seeds(parent).stream().map(store::release).noneMatch(r -> !r.publishedAt().isBefore(clock.instant().minus(Duration.ofDays(7)))))continue;
                    String id=SourceInputs.correctionId(parent.id());guard.check();if(!store.reserveArticle(budgetKey(),id,config.maxArticles()))break;
                    var sources=parent.sourceIds().stream().map(store::release).toList();
                    String hash=Normalize.hash(Json.write(List.of(parent,sources,ai.editorModel(),SourceInputs.CORRECTION_POLICY)));
                    String inputPath="corrections/"+id+"/input.json";
                    if(store.optionalBlob("articles",inputPath)==null) {guard.check();store.putBlob("articles",inputPath,Json.write(new CorrectionInput(parent.id(),hash,ai.editorModel(),clock.instant())));}
                    var correction=new Article(id,"sprava-"+id.substring(0,16),"Pripravuje sa oprava","",parent.category(),clock.instant(),null,null,Status.DRAFT,"",
                        parent.sourceIds(),parent.sourceUrls(),ai.model().startsWith("mock")?ai.model():ai.editorModel(),null,null,null,"",false,false,List.of(),List.of(),"CORRECTING",
                        store.budget(budgetKey()).articleIds().indexOf(id),day());guard.check();store.createArticle(correction);
                }
                // Repair a crash between saving a completed draft and marking its input material consumed.
                var incomplete=new ArrayList<Article>();
                for(Article a:store.editorial()) {
                    if(a.phase().equals("COMPLETE") || a.status()==Status.REJECTED) { for(String id:SourceInputs.seeds(a)) { guard.check(); store.releaseStatus(store.release(id),"DONE",null); } }
                    else incomplete.add(a);
                }
                Set<String> claimed=new HashSet<>();
                for(Article a:incomplete) {
                    claimed.addAll(SourceInputs.seeds(a));
                    try {
                        if(process(a,a.sourceIds().stream().map(store::release).toList(),guard,deadline)) {
                            drafts++;
                            if(config.researchFlow() && config.autoPublish()) {
                                var ready=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors);published+=ready.published();rejected+=ready.rejected();
                            }
                        }
                    }
                    catch(Exception e) { LOG.log(Level.WARNING,"Failed to resume article "+a.id(),e);errors.add("resume "+a.id()+": "+errorDescription(e)); }
                }
                var pending=store.pending(config.maxReleases());
                Instant freshSince=clock.instant().minus(Duration.ofDays(7));
                for(var source:pending) if(source.publishedAt().isBefore(freshSince)) {
                    guard.check();store.releaseStatus(source,"CONTEXT",null);
                }
                List<Release> candidates=pending.stream().filter(s -> !claimed.contains(s.id()) && !s.publishedAt().isBefore(freshSince)).toList();
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
                List<Release> archive=selected.isEmpty()?List.of():store.recent(clock.instant().minus(Duration.ofDays(90)),100);
                Set<String> consumed=new HashSet<>();
                for(var seedGroup:selected) {
                    if(Instant.now().isAfter(deadline)) break;
                    var seeds=seedGroup.stream().filter(r -> !consumed.contains(r.id())).limit(6).toList();
                    if(seeds.isEmpty()) continue;
                    var group=config.researchFlow()?seeds:Research.related(seeds,archive);
                    String id=Normalize.hash(seeds.stream().map(Release::id).sorted().reduce((a,b) -> a+"|"+b).orElseThrow());
                    if(store.article(id)!=null) continue;
                    guard.check(); String day=day();
                    if(!store.reserveArticle(budgetKey(),id,config.maxArticles())) break;
                    var initial=new Article(id,"sprava-"+id.substring(0,16),"Pripravuje sa", "",store.analysis(group.getFirst()).category(),clock.instant(),null,null,Status.DRAFT,
                        "",group.stream().map(Release::id).toList(),group.stream().map(Release::originalUrl).distinct().toList(),ai.model(),
                        null,null,null,"",false,false,List.of(),List.of(),config.researchFlow()?"DRAFTING":"GENERATING",store.budget(budgetKey()).articleIds().indexOf(id),day);
                    guard.check(); if(!store.createArticle(initial)) continue;
                    seeds.forEach(r -> consumed.add(r.id()));
                    try {
                        if(process(initial,group,guard,deadline)) {
                            drafts++;
                            if(config.researchFlow() && config.autoPublish()) {
                                var ready=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors);published+=ready.published();rejected+=ready.rejected();
                            }
                        }
                    }
                    catch(Exception e) { LOG.log(Level.WARNING,"Failed to draft article "+id,e);errors.add("draft "+id+": "+errorDescription(e)); }
                }
            } catch(Exception e) { errors.add("pipeline: "+e.getClass().getSimpleName()); }
            // An unrelated ranking/generation failure must not strand a cached, reviewed publication.
            if(config.autoPublish()) {
                var result=new AutomaticEditor(store,ai,config,clock,scope).run(guard,deadline,errors);
                published+=result.published(); rejected+=result.rejected();
            }
        } catch(Exception e) { errors.add("pipeline: "+e.getClass().getSimpleName()); }
        var result=new RunResult(start,clock.instant(),collected,drafts,published,rejected,List.copyOf(errors)); store.run(result,scope.label()); return result;
    }
    boolean process(Article initial,List<Release> group,Store.Guard guard,Instant deadline) {
        guard.check(); if(!store.reserveArticle(budgetKey(),initial.id(),config.maxArticles())) return false;
        var stored=store.article(initial.id()); Article a=stored.value();
        if(a.phase().equals("COMPLETE")) return false;
        Draft draft;List<Release> verificationSources=group;
        if(a.phase().equals("DRAFTING")) {
            var outcome=ResearchFlow.run(a,group,store,ai,lookup,guard,deadline,new ResearchFlow.Paid(){
                public <T> T call(Supplier<T> action){return paid(action,guard,deadline);}
            });
            draft=outcome.draft();verificationSources=outcome.sources();
            String checkpoint="drafts/"+a.id()+"/generated.json";guard.check();store.putBlob("articles",checkpoint,Json.write(draft));
            var next=new Article(a.id(),Normalize.slug(draft.title())+"-"+a.id().substring(0,12),draft.title(),draft.perex(),draft.category(),a.createdAt(),clock.instant(),null,Status.DRAFT,
                checkpoint,verificationSources.stream().map(Release::id).toList(),verificationSources.stream().map(Release::originalUrl).distinct().toList(),a.aiModel(),
                null,null,null,"",false,outcome.brief().sensitive(),List.of(),draft.claims(),"VERIFYING",a.editorialOrder(),a.editionDate());
            guard.check();store.updateArticle(next,stored.version());stored=store.article(a.id());a=stored.value();
        } else if(a.phase().equals("REVISING")) {
            var order=Newsroom.order(store,a.id());
            if(order==null || order.parentId()==null || !a.id().equals(Newsroom.revisionId(order.parentId())))throw new IllegalStateException("Invalid editorial revision");
            var parent=store.article(order.parentId()).value();
            if(parent.status()!=Status.REJECTED || parent.sensitive())throw new IllegalStateException("Revision parent unavailable");
            var original=new Draft(parent.title(),parent.perex(),parent.category(),store.blob("articles",parent.blobPath()),parent.claims());
            String checkpoint="editorial-revisions/"+a.id()+"/generated.json";String cached=store.optionalBlob("articles",checkpoint);
            draft=cached==null?paid(() -> ai.correct(original,group,order.instructions()),guard,deadline):Json.read(cached,Draft.class);Validation.draft(draft);
            guard.check();if(cached==null)store.putBlob("articles",checkpoint,Json.write(draft));
            var institutions=group.stream().collect(java.util.stream.Collectors.toMap(Release::id,s->ResearchRegistry.group(s.sourceId())));
            var parentGroups=parent.claims().stream().map(c->institutions.get(c.sourceId())).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            var revisedGroups=draft.claims().stream().map(c->institutions.get(c.sourceId())).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            if(!revisedGroups.containsAll(parentGroups))throw new IllegalArgumentException("Editorial revision omitted an original perspective");
            guard.check();store.putBlob("articles",checkpoint,Json.write(draft));
            var next=new Article(a.id(),Normalize.slug(draft.title())+"-"+a.id().substring(0,12),draft.title(),draft.perex(),draft.category(),a.createdAt(),clock.instant(),null,Status.DRAFT,
                checkpoint,a.sourceIds(),a.sourceUrls(),a.aiModel(),null,null,null,"",false,false,List.of(),draft.claims(),"VERIFYING",a.editorialOrder(),a.editionDate());
            guard.check();store.updateArticle(next,stored.version());stored=store.article(a.id());a=stored.value();
        } else if(a.phase().equals("CORRECTING")) {
            var input=Json.read(store.blob("articles","corrections/"+a.id()+"/input.json"),CorrectionInput.class);
            var parent=store.article(input.parentId()).value();
            String hash=Normalize.hash(Json.write(List.of(parent,group,ai.editorModel(),SourceInputs.CORRECTION_POLICY)));
            if(!a.id().equals(SourceInputs.correctionId(parent.id())) || !SourceInputs.correctionCandidate(parent) || !hash.equals(input.inputHash()) || !ai.editorModel().equals(input.model()))
                throw new IllegalStateException("Invalid citation correction input");
            var original=new Draft(parent.title(),parent.perex(),parent.category(),store.blob("articles",parent.blobPath()),parent.claims());
            if(!Normalize.hash(original.markdown()).equals(parent.contentHash()))throw new IllegalStateException("Rejected draft changed before correction");
            String checkpoint="corrections/"+a.id()+"/generated.json";String cached=store.optionalBlob("articles",checkpoint);
            draft=cached==null?paid(() -> ai.correct(original,group,parent.issues()),guard,deadline):Json.read(cached,Draft.class);Validation.draft(draft);
            var institutions=group.stream().collect(java.util.stream.Collectors.toMap(Release::id,s -> ResearchRegistry.group(s.sourceId())));
            if(parent.claims().stream().map(c -> institutions.get(c.sourceId())).filter(Objects::nonNull).distinct().count()>=2)
                Research.coverage(draft,new ResearchBrief(true,false,"",List.of(),List.of(),List.of()),group);
            guard.check();store.putBlob("articles",checkpoint,Json.write(draft));
            var next=new Article(a.id(),Normalize.slug(draft.title())+"-"+a.id().substring(0,12),draft.title(),draft.perex(),draft.category(),a.createdAt(),clock.instant(),null,Status.DRAFT,
                checkpoint,a.sourceIds(),a.sourceUrls(),a.aiModel(),null,null,null,"",false,false,List.of(),draft.claims(),"VERIFYING",a.editorialOrder(),a.editionDate());
            guard.check();store.updateArticle(next,stored.version());stored=store.article(a.id());a=stored.value();
        } else if(a.phase().equals("GENERATING")) {
            String checkpoint="drafts/"+a.id()+"/generated.json";
            String recovered=store.optionalBlob("articles",checkpoint);
            ResearchBrief brief=null;
            if(Research.multiSource(group)) {
                String inputHash=Normalize.hash(Json.write(List.of(group,ai.editorModel(),Research.POLICY_VERSION)));
                String researchPath="research/"+a.id()+"/"+inputHash+".json";
                String cached=store.optionalBlob("articles",researchPath);
                if(cached==null && recovered==null) {
                    brief=paid(() -> ai.research(group),guard,deadline);
                    try { Research.validate(brief,group); }
                    catch(IllegalArgumentException invalid) {
                        guard.check();store.putBlob("articles","research-errors/"+a.id()+"/"+UUID.randomUUID()+".json",
                            Json.write(new Research.FailedAttempt(inputHash,ai.editorModel(),errorDescription(invalid),brief)));
                        if(!"Ungrounded research number".equals(invalid.getMessage())) throw invalid;
                        // Drop unsupported observations, never waive the evidence check or invent a replacement citation.
                        brief=Research.discardUnsupportedNumbers(brief,group);
                    }
                    guard.check();store.putBlob("articles",researchPath,Json.write(new Research.Audit(inputHash,ai.editorModel(),Research.POLICY_VERSION,brief)));
                } else if(cached!=null) {
                    var audit=Json.read(cached,Research.Audit.class);
                    if(!inputHash.equals(audit.inputHash()) || !ai.editorModel().equals(audit.model()) || !Research.POLICY_VERSION.equals(audit.policyVersion())) throw new IllegalStateException("Invalid research audit");
                    brief=audit.brief();Research.validate(brief,group);
                }
            }
            ResearchBrief research=brief;
            draft=recovered==null?paid(() -> ai.generate(group,research),guard,deadline):Json.read(recovered,Draft.class); Validation.draft(draft);
            Research.coverage(draft,brief,group);
            guard.check(); store.putBlob("articles",checkpoint,Json.write(draft));
            var next=new Article(a.id(),Normalize.slug(draft.title())+"-"+a.id().substring(0,12),draft.title(),draft.perex(),draft.category(),a.createdAt(),clock.instant(),null,Status.DRAFT,
                checkpoint,a.sourceIds(),a.sourceUrls(),a.aiModel(),null,null,null,"",false,brief!=null && brief.sensitive(),List.of(),draft.claims(),"VERIFYING",a.editorialOrder(),a.editionDate());
            guard.check(); store.updateArticle(next,stored.version()); stored=store.article(a.id()); a=stored.value();
        } else draft=Json.read(store.blob("articles",a.blobPath()),Draft.class);
        var commissioned=config.newsroomFlow()?Newsroom.order(store,a.id()):null;
        if(commissioned!=null && commissioned.parentId()==null) {
            draft=DraftRepair.run(a.id(),draft,verificationSources,a.sensitive(),store,ai,guard,new DraftRepair.Paid(){
                public <T> T call(Supplier<T> action){return paid(action,guard,deadline);}
            });
        }
        Draft finalDraft=draft;
        List<Release> finalSources=verificationSources;
        Verification verification=Validation.grounded(draft,finalSources,paid(() -> ai.verify(finalDraft,finalSources),guard,deadline));
        if(a.sensitive()) verification=new Verification(verification.passed(),true,verification.issues());
        String hash=Normalize.hash(draft.markdown()), path="drafts/"+a.id()+"/"+hash+".md";
        guard.check(); store.putBlob("articles",path,draft.markdown());
        guard.check(); store.updateArticle(a.revise(draft,path,hash,verification,clock.instant()),stored.version());
        var order=Newsroom.order(store,a.id());
        for(String id:order==null?SourceInputs.seeds(a):List.of(order.pitch().primaryId())) { guard.check(); store.releaseStatus(store.release(id),"DONE",null); }
        return true;
    }
    private <T> T paid(Supplier<T> action,Store.Guard guard,Instant deadline) {
        guard.check(); if(Instant.now().plusSeconds(config.aiTimeout()+15).isAfter(deadline)) throw new IllegalStateException("Pipeline time budget reached");
        if(!store.reserveCall(budgetKey(),config.maxCalls())) throw new IllegalStateException("Execution AI call budget reached");
        return action.get();
    }
    private String day() { return clock.instant().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString(); }
    private String budgetKey(){return scope.budgetKey(clock);}
    static String errorDescription(Exception error) {
        String message=error.getMessage();
        // Only known fixed adapter/validation messages enter operational metadata, never arbitrary SDK data.
        var safe=Set.of("Invalid research brief","Invalid research finding","Unknown research evidence identifier","Ungrounded research number",
            "Analytical brief needs evidence from different institutions","Analytical draft omitted its second source","AI refusal or incomplete response",
            "Invalid JSON for ResearchBrief","Research needs 2-6 documents from different institutions","Unknown source evidence identifier",
            "Incomplete article generation","Invalid AI draft structure","Invalid claim map","Invalid research plan","Unknown research site",
            "Researched draft omitted a relevant institution","Editorial revision omitted an original perspective",
            "Claim map too large","Correction source input budget exceeded","Research input budget exceeded","Invalid researched claim","Invalid researched citation",
            "Invalid chief editor decision","Contradictory chief editor approval","Invalid chief editor reason","Invalid commissions",
            "Current original cannot be extracted","Current original text outside bounds","Current original absent from official feed","Freshness budget reached");
        return error.getClass().getSimpleName()+(message!=null && safe.contains(message)?": "+message:"");
    }
}
