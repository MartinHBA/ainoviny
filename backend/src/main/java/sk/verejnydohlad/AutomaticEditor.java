package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import sk.verejnydohlad.Models.*;

/** One bounded chief-editor call per content/source revision. No tools or autonomous retry loop. */
public final class AutomaticEditor {
    private static final Logger LOG=Logger.getLogger(AutomaticEditor.class.getName());
    static final String REVIEWER_PREFIX="AI editor: ", POLICY_VERSION="chief-editor-v2-multi-citation";
    public record Audit(String inputHash,String model,String policyVersion,Instant reviewedAt,EditorDecision decision) {}
    public record Result(int published,int rejected) {}
    private final Store store; private final Ai ai; private final Config config; private final Clock clock;private final RunScope scope;
    public AutomaticEditor(Store store,Ai ai,Config config,Clock clock) {this(store,ai,config,clock,RunScope.scheduled());}
    AutomaticEditor(Store store,Ai ai,Config config,Clock clock,RunScope scope) { this.store=store;this.ai=ai;this.config=config;this.clock=clock;this.scope=scope; }

    Result run(Store.Guard guard,Instant deadline,List<String> errors) {
        return run(guard,deadline,errors,true);
    }
    Result run(Store.Guard guard,Instant deadline,List<String> errors,boolean publish) {
        int published=0,rejected=0;
        var candidates=store.editorial().stream().filter(a -> a.phase().equals("COMPLETE") &&
            (a.status()==Status.DRAFT || publish && a.status()==Status.REVIEWED && isAutomatic(a)))
            .sorted(Comparator.comparing(Article::createdAt).thenComparing(Article::id)).toList();
        for(var candidate:candidates) {
            if(Instant.now().isAfter(deadline)) break;
            var editorial=new Editorial(store,clock,config.maxPublished(),scope);
            if(publish && editorial.publicationLimitReached()) break;
            try {
                guard.check();
                String day=scope.budgetKey(clock);
                if(!store.reserveArticle(day,candidate.id(),config.maxArticles())) continue;
                var old=store.article(candidate.id()); Article a=old.value();
                Draft draft=new Draft(a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims());
                if(!Normalize.hash(draft.markdown()).equals(a.contentHash())) throw new IllegalStateException("Draft changed after verification");
                List<Release> sources=a.sourceIds().stream().map(store::release).toList();
                Verification grounding=Validation.grounded(draft,sources,new Verification(a.factCheckPassed(),a.sensitive(),a.issues()));
                String model=ai.editorModel();
                if(model.equals(ai.model())) throw new IllegalStateException("Chief editor must use a separate deployment");
                if(a.aiModel().startsWith("mock") && !config.storageConnection().equals("UseDevelopmentStorage=true"))
                    throw new IllegalStateException("Mock articles may be published only to local Azurite");
                if(a.status()==Status.DRAFT && (!grounding.passed() || grounding.sensitive())) {
                    var issues=new ArrayList<>(grounding.issues());
                    if(grounding.sensitive()) issues.add("Sensitive allegations are excluded from automatic publication.");
                    reject(a,draft,new Verification(false,grounding.sensitive(),List.copyOf(issues)),old.version(),"Automatic checks",guard);
                    rejected++; continue;
                }
                String approvalHash=Editorial.approvalHash(a,draft.markdown());
                // Include full originals and policy/model identity, so cached decisions cannot approve changed inputs.
                String inputHash=Normalize.hash(Json.write(List.of(approvalHash,sources,model,POLICY_VERSION)));
                String path="reviews/"+a.id()+"/"+inputHash+".json";
                String cached=store.optionalBlob("articles",path);
                Audit audit;
                if(cached==null) {
                    if(a.status()==Status.REVIEWED) throw new IllegalStateException("Missing or outdated automatic approval audit");
                    if(Instant.now().plusSeconds(config.aiTimeout()+15).isAfter(deadline)) break;
                    guard.check();
                    if(!store.reserveCall(day,config.maxCalls())) break;
                    EditorDecision decision=ai.review(draft,sources); Validation.editorDecision(decision);
                    audit=new Audit(inputHash,model,POLICY_VERSION,clock.instant(),decision);
                    guard.check(); store.putBlob("articles",path,Json.write(audit));
                } else audit=Json.read(cached,Audit.class);
                Validation.editorDecision(audit.decision());
                if(!audit.inputHash().equals(inputHash) || !audit.model().equals(model) || !audit.policyVersion().equals(POLICY_VERSION) || audit.reviewedAt()==null)
                    throw new IllegalStateException("Invalid automatic approval audit");
                if(!audit.decision().approved()) {
                    if(a.status()!=Status.DRAFT) throw new IllegalStateException("Automatic approval contradicts audit");
                    var issues=new ArrayList<>(audit.decision().issues()); issues.add(audit.decision().reason());
                    reject(a,draft,new Verification(false,audit.decision().sensitive(),List.copyOf(issues)),old.version(),REVIEWER_PREFIX+model,guard);
                    rejected++; continue;
                }
                if(!grounding.passed() || grounding.sensitive()) throw new IllegalStateException("Automatic publication grounding failed");
                if(a.status()==Status.DRAFT) {
                    guard.check(); store.updateArticle(a.state(Status.REVIEWED,audit.reviewedAt(),REVIEWER_PREFIX+model,approvalHash,null,a.blobPath()),old.version());
                } else if(!approvalHash.equals(a.approvedHash()) || !a.reviewer().equals(REVIEWER_PREFIX+model))
                    throw new IllegalStateException("Automatic approval no longer matches article");
                if(publish){editorial.publish(a.id(),guard); published++;}
            } catch(Exception e) {
                LOG.log(Level.WARNING,"Chief editor failed for article "+candidate.id(),e);
                errors.add("editor "+candidate.id()+": "+e.getClass().getSimpleName());
            }
        }
        return new Result(published,rejected);
    }
    private void reject(Article a,Draft d,Verification v,String version,String reviewer,Store.Guard guard) {
        var rejected=a.revise(d,a.blobPath(),a.contentHash(),v,clock.instant())
            .state(Status.REJECTED,clock.instant(),reviewer,null,null,a.blobPath());
        guard.check(); store.updateArticle(rejected,version);
    }
    static boolean isAutomatic(Article a) { return a.reviewer()!=null && a.reviewer().startsWith(REVIEWER_PREFIX); }
}
