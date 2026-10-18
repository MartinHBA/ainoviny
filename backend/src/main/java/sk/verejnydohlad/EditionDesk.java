package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import sk.verejnydohlad.Models.*;

/** Batch-level semantic selection. Routing never edits an original article or grants approval. */
final class EditionDesk {
    static final String POLICY="edition-synthesis-v1";
    static final String SELECTION_POLICY="edition-selection-v2-concrete-event";
    interface Paid {<T>T call(Supplier<T> action);}
    record Parent(String id,String hash,Draft draft) {}
    record MergeInput(List<Parent> parents,List<Release> sources,List<String> instructions,String writerModel,String editorModel,String policy,Instant expiresAt) {}
    record MergeRef(String inputPath,String inputHash,List<String> parentIds) {}
    static boolean routed(Store store,String id){
        String raw=store.operational("edition-route|"+id);
        return raw!=null && !Json.tree(raw).path("action").asText().equals("DISTINCT");
    }
    private static boolean reassessableHold(Store store,String id){
        String raw=store.operational("edition-route|"+id);if(raw==null)return false;
        var route=Json.tree(raw);
        return route.path("action").asText().equals("HOLD") &&
            route.path("reason").asText().equals("Further consolidation requires a new editorial assignment") &&
            !route.path("policy").asText().equals(SELECTION_POLICY);
    }
    static String mergeId(List<String> ids){return Normalize.hash(POLICY+"|"+String.join("|",ids.stream().sorted().toList()));}
    static List<String> prepare(Store store,Ai ai,Config config,Clock clock,String budgetKey,Store.Guard guard,Paid paid,List<String> blockers) {
        var all=store.editorial();
        var pending=all.stream().filter(a->a.status()==Status.REVIEWED && AutomaticEditor.isAutomatic(a) && !a.sensitive() &&
            (!routed(store,a.id()) || reassessableHold(store,a.id())) && active(store,a,clock)).sorted(Comparator.comparing(Article::createdAt).thenComparing(Article::id)).toList();
        if(pending.isEmpty())return List.of();
        var today=clock.instant().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate();
        var published=all.stream().filter(a->a.status()==Status.PUBLISHED && a.publishedAt()!=null &&
            a.publishedAt().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().equals(today)).sorted(Comparator.comparing(Article::id)).toList();
        if(pending.size()==1 && published.isEmpty() && !reassessableHold(store,pending.getFirst().id()))return List.of();
        if(pending.size()>12 || published.size()>24)throw new IllegalStateException("Edition selection input budget exceeded");
        var proposals=pending.stream().map(a->item(store,a)).toList();var history=published.stream().map(a->item(store,a)).toList();
        if(Json.write(List.of(proposals,history)).length()>200000)throw new IllegalStateException("Edition selection input budget exceeded");
        String hash=Normalize.hash(Json.write(List.of(SELECTION_POLICY,today.toString(),ai.editorModel(),proposals,history)));
        String path="edition-plans/"+hash+".json";String raw=store.optionalBlob("articles",path);
        EditionPlan plan;
        if(raw==null) {
            String proposalPath="edition-plans/"+hash+".proposed.json";
            String cached=store.optionalBlob("articles",proposalPath);
            var proposed=cached==null?paid.call(()->ai.edition(proposals,history)):Json.read(cached,EditionPlan.class);
            validate(proposed,proposals,history);guard.check();
            if(cached==null)store.putBlob("articles",proposalPath,Json.write(proposed));
            plan=proposed.groups().isEmpty()?proposed:paid.call(()->ai.reviewEdition(proposed,proposals,history));
            validate(plan,proposals,history);validateReview(proposed,plan);
        } else plan=Json.read(raw,EditionPlan.class);
        validate(plan,proposals,history);guard.check();if(raw==null)store.putBlob("articles",path,Json.write(plan));
        // A previous broad chain HOLD may be reconsidered once under the stricter policy. Keep the
        // old plan immutable; only an audited fresh decision can make its unlisted article distinct.
        var grouped=plan.groups().stream().flatMap(g->g.articleIds().stream()).collect(java.util.stream.Collectors.toSet());
        for(var a:pending)if(reassessableHold(store,a.id()) && !grouped.contains(a.id())) {
            String previous=store.operational("edition-route|"+a.id());
            route(store,a.id(),Map.of("action","DISTINCT","plan",path,"policy",SELECTION_POLICY,"previous",Json.tree(previous),"reason","Reassessed under concrete-event policy"),guard);
        }
        var byId=new HashMap<String,Article>();pending.forEach(a->byId.put(a.id(),a));var children=new ArrayList<String>();
        for(var group:plan.groups()) {
            guard.check();
            if(group.action().equals("DUPLICATE")) {
                for(String id:group.articleIds())route(store,id,Map.of("action","DUPLICATE","publishedArticleId",group.publishedArticleId(),"plan",path,"reason",group.reason()),guard);
                blockers.add("Same story already published: "+String.join(",",group.articleIds()));continue;
            }
            var parents=group.articleIds().stream().sorted().map(byId::get).toList();String id=mergeId(group.articleIds());
            // A merged article never starts an automatic merge chain.
            if(parents.stream().anyMatch(a->store.operational("merge|"+a.id())!=null)) {
                for(var parent:parents)route(store,parent.id(),Map.of("action","HOLD","plan",path,"policy",SELECTION_POLICY,"reason","Further consolidation requires a new editorial assignment"),guard);
                blockers.add("Further edition merge held: "+id);continue;
            }
            var snapshots=parents.stream().map(a->{var d=draft(store,a);return new Parent(a.id(),Normalize.hash(Json.write(d)),d);}).toList();
            var cited=snapshots.stream().flatMap(p->p.draft().claims().stream()).map(Claim::sourceId).distinct().sorted().toList();
            var sources=cited.stream().map(store::release).toList();
            Instant expires=parents.stream().map(a->expiry(store,a)).min(Comparator.naturalOrder()).orElseThrow();
            var input=new MergeInput(snapshots,sources,group.instructions(),ai.model(),ai.editorModel(),POLICY,expires);
            String inputPath="edition-merges/"+id+"/input.json";String inputHash=Normalize.hash(Json.write(input));
            var ref=new MergeRef(inputPath,inputHash,parents.stream().map(Article::id).toList());
            if(sources.size()>6 || sources.stream().mapToLong(s->s.body().length()).sum()>200000) {
                for(var parent:parents)route(store,parent.id(),Map.of("action","HOLD","plan",path,"reason","Edition merge source budget exceeded"),guard);
                blockers.add("Edition merge source budget exceeded: "+id);continue;
            }
            // Persist the job before routing parents. A crash after routing can always recover the child.
            store.putBlob("articles",inputPath,Json.write(input));
            store.operational("merge|"+id,Json.write(ref));
            var pitch=new StoryPitch(cited.getFirst(),cited,"Zlúčiť prekrývajúce sa koncepty do jedného doloženého článku.","ANALYSIS",90,"Edition editor consolidation");
            var order=new StoryOrder(id,pitch,null,clock.instant(),expires,group.instructions());
            String orderRaw=store.operational("story|"+id);
            if(orderRaw==null)store.operational("story|"+id,Json.write(order));
            for(var parent:parents)route(store,parent.id(),Map.of("action","MERGE","articleId",id,"plan",path,"reason",group.reason()),guard);
            if(!store.reserveArticle(budgetKey,id,config.maxArticles())) {blockers.add("Edition merge concept budget reached: "+id);continue;}
            children.add(id);
        }
        return List.copyOf(children);
    }
    private static EditionItem item(Store store,Article a){return new EditionItem(a.id(),a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims().stream().map(Claim::sourceId).distinct().sorted().toList());}
    private static Draft draft(Store store,Article a){return new Draft(a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims());}
    private static Instant expiry(Store store,Article a){var order=Newsroom.order(store,a.id());return order==null?a.generatedAt().plus(Duration.ofDays(2)):order.expiresAt();}
    private static boolean active(Store store,Article a,Clock clock){return a.generatedAt()!=null && clock.instant().isBefore(expiry(store,a));}
    private static void route(Store store,String id,Object value,Store.Guard guard){guard.check();store.operational("edition-route|"+id,Json.write(value));}
    static MergeInput input(Store store,String id,Ai ai,Store.Guard guard) {
        var ref=Json.read(store.operational("merge|"+id),MergeRef.class);var input=Json.read(store.blob("articles",ref.inputPath()),MergeInput.class);
        if(!ref.inputHash().equals(Normalize.hash(Json.write(input))) || !id.equals(mergeId(ref.parentIds())) || !input.policy().equals(POLICY) ||
            !input.parents().stream().map(Parent::id).sorted().toList().equals(ref.parentIds().stream().sorted().toList()) ||
            !input.writerModel().equals(ai.model()) || !input.editorModel().equals(ai.editorModel()))throw new IllegalStateException("Invalid edition merge audit");
        for(var parent:input.parents()) {
            var article=store.article(parent.id()).value();
            if(article.status()==Status.PUBLISHED || article.sensitive() || !parent.hash().equals(Normalize.hash(Json.write(parent.draft()))) || !parent.hash().equals(Normalize.hash(Json.write(draft(store,article)))))throw new IllegalStateException("Edition merge parent changed");
        }
        for(String parentId:ref.parentIds())if(!routed(store,parentId))route(store,parentId,Map.of("action","MERGE","articleId",id,"reason","Recovered edition merge assignment"),guard);
        return input;
    }
    static void validate(EditionPlan plan,List<EditionItem> pending,List<EditionItem> published) {
        if(plan==null || plan.groups()==null || plan.groups().size()>12)throw new IllegalArgumentException("Invalid edition plan");
        var ids=pending.stream().map(EditionItem::id).collect(java.util.stream.Collectors.toSet());var history=published.stream().map(EditionItem::id).collect(java.util.stream.Collectors.toSet());
        var used=new HashSet<String>();int merges=0;
        for(var g:plan.groups()) {
            if(g==null || g.action()==null || g.articleIds()==null || g.articleIds().isEmpty() || g.articleIds().size()>3 ||
                new HashSet<>(g.articleIds()).size()!=g.articleIds().size() || !ids.containsAll(g.articleIds()) ||
                g.instructions()==null || g.instructions().size()>8 || g.instructions().stream().anyMatch(s->s==null || s.isBlank() || s.length()>700) ||
                g.reason()==null || g.reason().isBlank() || g.reason().length()>1500 || g.articleIds().stream().anyMatch(id->!used.add(id)))throw new IllegalArgumentException("Invalid edition group");
            if(g.action().equals("MERGE")) {if(++merges>2 || g.articleIds().size()<2 || g.publishedArticleId()!=null || g.instructions().isEmpty())throw new IllegalArgumentException("Invalid edition merge");}
            else if(!g.action().equals("DUPLICATE") || !history.contains(g.publishedArticleId()))throw new IllegalArgumentException("Invalid edition duplicate");
        }
    }
    static void validateReview(EditionPlan proposed,EditionPlan reviewed) {
        if(!proposed.groups().containsAll(reviewed.groups()))throw new IllegalArgumentException("Edition review changed a proposed group");
    }
    private EditionDesk() {}
}
