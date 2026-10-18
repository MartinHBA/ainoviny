package sk.verejnydohlad;

import java.time.Instant;
import java.util.*;
import sk.verejnydohlad.Models.*;

/** Isolates a complete editorial cycle while retaining the shared immutable source archive. */
final class EditionWorkStore implements Store {
    final Store base;final EditionRevision revision;final Guard lease;final boolean staging;
    EditionWorkStore(Store base,EditionRevision revision,Guard lease,boolean staging){this.base=base;this.revision=revision;this.lease=lease;this.staging=staging;}
    String prefix(){return revision==null?"":"revision|"+revision.id()+"|";}
    String path(String c,String p){String root=revision==null || !c.equals("articles")?"":"reeditions/"+revision.id()+"/";return p.isEmpty() || p.startsWith("reeditions/") || p.startsWith(root)?p:root+p;}
    String budgetKey(String key){return revision==null?key:"edition|"+revision.date()+"|"+revision.id();}
    Article tagged(Article a){return revision==null?a:Models.withRevision(a,revision.id(),revision.date(),path("articles",a.blobPath()));}
    boolean visible(Article a){return a.editionRevision()==null || a.status()==Status.PUBLISHED && base.bySlug(a.slug())!=null;}
    public String revisionId(){return revision==null?null:revision.id();}
    public String workId(String id){return revision==null?id:Normalize.hash("edition|"+revision.id()+"|"+id);}
    public void initialize(List<Source>s){base.initialize(s);}
    public Guard lock(){lease.check();return new Guard(){public void check(){lease.check();}public void close(){}};}
    public void putBlob(String c,String p,String v){base.putBlob(c,path(c,p),v);}
    public String blob(String c,String p){return base.blob(c,path(c,p));}
    public String optionalBlob(String c,String p){return base.optionalBlob(c,path(c,p));}
    public boolean knownRelease(String id){return base.knownRelease(id);}
    public boolean collect(Release r){return base.collect(r);}
    public Release release(String id){return base.release(id);}
    public List<Release> pending(int n){return revision==null?base.pending(n):base.recent(Instant.EPOCH,n);}
    public List<Release> recent(Instant since,int n){return base.recent(since,n);}
    record SourceState(String status,Analysis analysis){}
    public void releaseStatus(Release r,String state,Analysis analysis){
        if(revision==null){base.releaseStatus(r,state,analysis);return;}
        operational("source-state|"+r.id(),Json.write(new SourceState(state,analysis==null?analysis(r):analysis)));
    }
    public String releaseStatus(Release r){String raw=revision==null?null:operational("source-state|"+r.id());return revision==null?base.releaseStatus(r):raw==null?"CONTEXT":Json.read(raw,SourceState.class).status();}
    public Analysis analysis(Release r){String raw=revision==null?null:operational("source-state|"+r.id());return revision==null?base.analysis(r):raw==null?null:Json.read(raw,SourceState.class).analysis();}
    public Versioned<Article> article(String id){return base.article(id);}
    public boolean createArticle(Article a){return base.createArticle(tagged(a));}
    public void updateArticle(Article a,String v){base.updateArticle(tagged(a),v);}
    public void publish(Article a,String v){publish(a,v,"scheduled");}
    public void publish(Article a,String v,String scope){if(staging)base.stage(tagged(a),v,scope);else base.publish(tagged(a),v,scope);}
    public String publicationScope(String id){return base.publicationScope(id);}
    public List<Article> editorial(){
        return base.editorial().stream().filter(a->revision==null?visible(a):revision.id().equals(a.editionRevision()) ||
            a.status()==Status.PUBLISHED && !revision.date().equals(a.editionDate()) && visible(a)).toList();
    }
    public Page published(int n,String cursor,String category){return base.published(n,cursor,category);}
    public Article bySlug(String slug){return base.bySlug(slug);}
    public Edition latest(){return base.latest();}
    public Budget budget(String key){return base.budget(budgetKey(key));}
    public boolean reserveCall(String key,int n){return base.reserveCall(budgetKey(key),n);}
    public boolean reserveArticle(String key,String id,int n){return base.reserveArticle(budgetKey(key),id,n);}
    public void sourceChecked(Source source,Instant at){base.sourceChecked(source,at);}
    public void run(RunResult result){base.run(result);}
    public void run(RunResult result,String scope){base.run(result,revision==null?scope:"reedition|"+revision.id());}
    public String operational(String key){return base.operational(prefix()+key);}
    public void operational(String key,String payload){base.operational(prefix()+key,payload);}
    public List<StoryOrder> orders(){return base.orders(prefix());}
}
