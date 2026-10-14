package sk.verejnydohlad;

import java.time.Instant;
import java.util.*;
import sk.verejnydohlad.Models.*;

class MemoryStore implements Store {
    final Map<String,String> blobs=new HashMap<>(), statuses=new HashMap<>();
    final Map<String,Release> releases=new LinkedHashMap<>(); final Map<String,Analysis> rankings=new HashMap<>();
    final Map<String,Versioned<Article>> articles=new HashMap<>(); final Map<String,Article> publicArticles=new HashMap<>();
    final Map<String,Budget> budgets=new HashMap<>(); boolean locked=false, failPublish=false; int version=0;
    final Map<String,String> publicationScopes=new HashMap<>();final List<String> runScopes=new ArrayList<>();
    public void initialize(List<Source> s) {}
    public Guard lock() { if(locked) throw new IllegalStateException("locked"); locked=true; return new Guard(){public void check(){if(!locked)throw new IllegalStateException("lost");}public void close(){locked=false;}}; }
    public void putBlob(String c,String p,String v) { String key=c+"/"+p; if(blobs.containsKey(key)&&!blobs.get(key).equals(v))throw new IllegalStateException("immutable"); blobs.put(key,v); }
    public String blob(String c,String p) { return Objects.requireNonNull(blobs.get(c+"/"+p)); }
    public String optionalBlob(String c,String p) { return blobs.get(c+"/"+p); }
    public boolean knownRelease(String id) { return releases.containsKey(id); }
    public boolean collect(Release r) { if(releases.containsKey(r.id())||releases.values().stream().anyMatch(s -> s.contentHash().equals(r.contentHash())))return false; releases.put(r.id(),r);putBlob("sources",r.blobPath(),Json.write(r));statuses.put(r.id(),"PENDING");return true; }
    public Release release(String id) { return Objects.requireNonNull(releases.get(id)); }
    public List<Release> pending(int max) { return releases.values().stream().filter(r -> Set.of("PENDING","ANALYZED").contains(statuses.get(r.id()))).limit(max).toList(); }
    public List<Release> recent(Instant since,int max) { return releases.values().stream().filter(r -> !r.publishedAt().isBefore(since) || r.publishedAt().equals(Instant.EPOCH) && !r.collectedAt().isBefore(since)).sorted(Comparator.comparing(Release::publishedAt).reversed()).limit(max).toList(); }
    public void releaseStatus(Release r,String s,Analysis a) {statuses.put(r.id(),s);if(a!=null)rankings.put(r.id(),a);}
    public Analysis analysis(Release r) {return rankings.get(r.id());}
    public String releaseStatus(Release r) {return statuses.get(r.id());}
    public Versioned<Article> article(String id) {return articles.get(id);}
    public boolean createArticle(Article a) {if(articles.containsKey(a.id()))return false;articles.put(a.id(),new Versioned<>(a,""+(++version)));return true;}
    public void updateArticle(Article a,String v) {if(!articles.get(a.id()).version().equals(v))throw new IllegalStateException("stale");articles.put(a.id(),new Versioned<>(a,""+(++version)));}
    public void publish(Article a,String v) {if(failPublish)throw new IllegalStateException("injected publication failure");updateArticle(a,v);publicArticles.put(a.slug(),a);}
    public void publish(Article a,String v,String scope){publish(a,v);publicationScopes.put(a.id(),scope);}
    public String publicationScope(String id){return publicationScopes.getOrDefault(id,"scheduled");}
    public List<Article> editorial() {return articles.values().stream().map(Versioned::value).toList();}
    public Page published(int size,String cursor,String category) {return new Page(publicArticles.values().stream().filter(a -> a.status()==Status.PUBLISHED && (category==null||category.equals(a.category()))).sorted(Comparator.comparing(Article::publishedAt).reversed()).limit(size).map(Summary::of).toList(),null);}
    public Article bySlug(String slug) {return publicArticles.get(slug);}
    public Edition latest() {var list=published(5,null,null).articles();return list.isEmpty()?new Edition(null,null,List.of()):new Edition(bySlug(list.getFirst().slug()).editionDate(),list.getFirst(),list.stream().skip(1).toList());}
    public Budget budget(String day) {return budgets.getOrDefault(day,new Budget(0,List.of()));}
    public boolean reserveCall(String day,int max) {var b=budget(day);if(b.calls()>=max)return false;budgets.put(day,new Budget(b.calls()+1,b.articleIds()));return true;}
    public boolean reserveArticle(String day,String id,int max) {var b=budget(day);if(b.articleIds().contains(id))return true;if(b.articleIds().size()>=max)return false;var ids=new ArrayList<>(b.articleIds());ids.add(id);budgets.put(day,new Budget(b.calls(),ids));return true;}
    public void sourceChecked(Source s,Instant at) {}
    public void run(RunResult r) {}
    public void run(RunResult r,String scope){run(r);runScopes.add(scope);}
}
