package sk.verejnydohlad;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import sk.verejnydohlad.Models.*;

public final class Editorial {
    private final Store store; private final Clock clock; private final int maxPublished;private final RunScope scope;
    public Editorial(Store store,Clock clock) { this(store,clock,Config.environment().maxPublished()); }
    public Editorial(Store store,Clock clock,int maxPublished) {this(store,clock,maxPublished,RunScope.scheduled());}
    Editorial(Store store,Clock clock,int maxPublished,RunScope scope) { this.store=store; this.clock=clock; this.maxPublished=maxPublished;this.scope=scope; }
    boolean publicationLimitReached() {
        String day=clock.instant().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString();
        return store.editorial().stream().filter(a -> a.status()==Status.PUBLISHED && a.publishedAt()!=null)
            .filter(a -> scope.manual()?scope.label().equals(store.publicationScope(a.id())):
                a.publishedAt().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString().equals(day) && !RunScope.manualLabel(store.publicationScope(a.id())))
            .count()>=maxPublished;
    }
    public void edit(String id,Draft draft) {
        Validation.id(id); Validation.draft(draft);
        try(var guard=store.lock()) {
            var old=required(id); editable(old.value());
            String hash=Normalize.hash(draft.markdown()), path="drafts/"+id+"/"+hash+".md";
            guard.check(); store.putBlob("articles",path,draft.markdown());
            var deterministic=Validation.grounded(draft,old.value().sourceIds().stream().map(store::release).toList(),new Verification(false,false,List.of("Edited content needs a new AI verification pass.")));
            guard.check(); store.updateArticle(old.value().revise(draft,path,hash,deterministic,clock.instant()),old.version());
        }
    }
    public void recheck(String id,Ai ai,Config config) {
        Validation.id(id);
        try(var guard=store.lock()) {
            var old=required(id); Article a=old.value(); editable(a);
            Draft d=new Draft(a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims());
            List<Release> sources=a.sourceIds().stream().map(store::release).toList();
            String day=clock.instant().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString();
            if(!store.reserveCall(day,config.maxCalls())) throw new IllegalStateException("Daily AI call budget reached");
            var verification=Validation.grounded(d,sources,ai.verify(d,sources));
            guard.check(); store.updateArticle(a.revise(d,a.blobPath(),Normalize.hash(d.markdown()),verification,clock.instant()),old.version());
        }
    }
    public void approve(String id,String reviewer,boolean sensitiveAcknowledged) {
        Validation.id(id); reviewer(reviewer);
        try(var guard=store.lock()) {
            var old=required(id); Article a=old.value();
            if(a.status()!=Status.DRAFT || !a.phase().equals("COMPLETE") || !a.factCheckPassed()) throw new IllegalStateException("Only a completed, grounded DRAFT can be approved");
            if(a.sensitive() && !sensitiveAcknowledged) throw new IllegalStateException("Sensitive claims require explicit --ack-sensitive human review");
            String content=store.blob("articles",a.blobPath());
            if(!Normalize.hash(content).equals(a.contentHash())) throw new IllegalStateException("Content changed after verification");
            guard.check(); store.updateArticle(a.state(Status.REVIEWED,clock.instant(),reviewer,approvalHash(a,content),null,a.blobPath()),old.version());
        }
    }
    public void reject(String id,String reviewer) {
        Validation.id(id); reviewer(reviewer);
        try(var guard=store.lock()) {
            var old=required(id); if(old.value().status()==Status.PUBLISHED || old.value().status()==Status.REJECTED) throw new IllegalStateException("Article cannot be rejected");
            guard.check(); store.updateArticle(old.value().state(Status.REJECTED,clock.instant(),reviewer,null,null,old.value().blobPath()),old.version());
        }
    }
    public void publish(String id) {
        Validation.id(id);
        try(var guard=store.lock()) {
            publish(id,guard);
        }
    }
    // Pipeline already owns the renewable global lease; do not acquire it a second time.
    void publish(String id,Store.Guard guard) {
        Validation.id(id);
        var old=required(id); Article a=old.value();
        if(a.status()==Status.PUBLISHED) return; // safe to retry after a lost response
        if(publicationLimitReached()) throw new IllegalStateException("Daily publication limit reached");
        if(a.status()!=Status.REVIEWED || a.reviewedAt()==null || a.reviewer()==null || !a.factCheckPassed()) throw new IllegalStateException("Editorial approval required");
        String content=store.blob("articles",a.blobPath());
        if(!approvalHash(a,content).equals(a.approvedHash())) throw new IllegalStateException("Article changed after approval");
        if(!a.aiModel().equals("mock-offline-synthetic") && a.title().contains("[SYNTHETIC]")) throw new IllegalStateException("Synthetic article must remain local");
        // Published bytes are private and unreachable until the three-row Table transaction commits.
        Instant now=clock.instant();
        String date=DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC).format(now);
        String path="published/"+date+"/"+a.id()+".md";
        guard.check(); store.putBlob("articles",path,content);
        guard.check(); store.publish(Models.withEdition(a.state(Status.PUBLISHED,a.reviewedAt(),a.reviewer(),a.approvedHash(),now,path),
            now.atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString()),old.version(),scope.label());
    }
    static String approvalHash(Article a,String markdown) {
        return Normalize.hash(Json.write(List.of(a.title(),a.perex(),a.category(),a.sourceUrls(),a.claims(),a.aiModel(),markdown)));
    }
    private Store.Versioned<Article> required(String id) { var a=store.article(id); if(a==null) throw new IllegalArgumentException("Article not found"); return a; }
    private static void editable(Article a) { if(a.status()==Status.PUBLISHED || a.status()==Status.REJECTED || !a.phase().equals("COMPLETE")) throw new IllegalStateException("Article is not editable"); }
    private static void reviewer(String name) { if(name==null || !name.matches("[\\p{L}\\p{N}@._ +'-]{3,120}")) throw new IllegalArgumentException("Named human reviewer required"); }
}
