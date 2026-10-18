package sk.verejnydohlad;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;

/** Local executable using Azure RBAC. No administrative HTTP endpoints are deployed. */
public final class Cli {
    public static void main(String[] args) throws Exception {
        if(args.length==0) throw new IllegalArgumentException("Commands: init, collect, run, prepare, newsroom, list, inspect ID, export ID FILE, edit ID FILE, recheck ID, approve ID REVIEWER [--ack-sensitive], reject ID REVIEWER, publish ID, fixture, check-feeds");
        if(args[0].equals("check-feeds")) { checkFeeds(); return; }
        App app=App.instance(); Store store=app.store; var editor=new Editorial(store,Clock.systemUTC());
        switch(args[0]) {
            case "init" -> store.initialize(SourceRegistry.load());
            case "newsroom" -> {
                System.out.println(Json.write(Map.of("orders",store.orders(),"articles",store.editorial().stream().map(a->Map.of("id",a.id(),"title",a.title(),"status",a.status(),"phase",a.phase(),"issues",a.issues())).toList())));
            }
            case "prepare" -> {
                if(!app.config.newsroomFlow())throw new IllegalStateException("Newsroom flow is disabled");
                System.out.println(Json.write(new Newsroom(store,app.ai,app.config,Clock.systemUTC(),app.config.aiMode().equals("azure")?new OfficialResearch():ResearchLookup.archiveOnly()).run(true,RunScope.scheduled(),true)));
            }
            case "run" -> {
                var scope=args.length>1?new RunScope(arg(args,1)):RunScope.adHoc();
                System.out.println(Json.write(Map.of("scope",scope.label(),"result",app.pipeline.run(true,scope))));
            }
            case "collect" -> { try(var guard=store.lock()) { var errors=new ArrayList<String>(); int n=new Collector().collect(SourceRegistry.load(),store,app.config.maxReleases(),guard,Instant.now().plusSeconds(120),errors); System.out.println(Json.write(Map.of("collected",n,"errors",errors))); } }
            case "list" -> store.editorial().stream().filter(a -> a.status()!=Status.PUBLISHED).forEach(a -> System.out.println(a.id()+" "+a.status()+" "+a.phase()+" grounded="+a.factCheckPassed()+" "+a.title()));
            case "inspect", "export" -> {
                Article a=article(store,arg(args,1)); Draft d=new Draft(a.title(),a.perex(),a.category(),a.phase().equals("COMPLETE")?store.blob("articles",a.blobPath()):"",a.claims());
                if(args[0].equals("export")) Files.writeString(Path.of(arg(args,2)),Json.write(d));
                else { System.out.println(Json.write(a)); System.out.println(Json.write(d)); for(String id:a.sourceIds()) System.out.println(Json.write(store.release(id))); }
            }
            case "edit" -> editor.edit(arg(args,1),Json.read(Files.readString(Path.of(arg(args,2))),Draft.class));
            case "recheck" -> editor.recheck(arg(args,1),app.ai,app.config);
            case "approve" -> editor.approve(arg(args,1),arg(args,2),Arrays.asList(args).contains("--ack-sensitive"));
            case "reject" -> editor.reject(arg(args,1),arg(args,2));
            case "publish" -> {
                Article a=article(store,arg(args,1));
                if(a.aiModel().startsWith("mock") && !app.config.storageConnection().equals("UseDevelopmentStorage=true")) throw new IllegalStateException("Mock articles may be published only to local Azurite");
                editor.publish(a.id());
            }
            case "fixture" -> {
                if(!app.config.aiMode().equals("mock") || !app.config.storageConnection().equals("UseDevelopmentStorage=true")) throw new IllegalStateException("Fixture is allowed only with mock AI and local Azurite");
                String body="Syntetická testovacia organizácia uvádza skúšobnú informáciu pre lokálny vývoj. Ide o označený testovací podklad, ktorý nie je skutočnou správou.";
                String url="https://example.invalid/synthetic-local-only",id=Normalize.hash(url),hash=Normalize.hash(body);
                store.collect(new Release(id,"synthetic","Syntetická testovacia organizácia",url,Instant.now(),Instant.now(),"[SYNTHETIC] Testovacia správa",body,hash,"synthetic/fixture/"+id+".json"));
                System.out.println(Json.write(app.pipeline.run(false)));
            }
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }
    private static String arg(String[] args,int index) { if(args.length<=index) throw new IllegalArgumentException("Missing command argument"); return args[index]; }
    private static Article article(Store store,String id) { Validation.id(id); var a=store.article(id); if(a==null) throw new IllegalArgumentException("Article not found"); return a.value(); }
    private static void checkFeeds() {
        var http=new SafeHttp(); var robots=new Robots(http);
        for(var s:SourceRegistry.load()) if(s.enabled()) {
            robots.requireAllowed(s.feedUrl(),s.allowedHosts()); var items=new FeedParser().parse(http.get(s.feedUrl(),s.allowedHosts()),s);
            if(items.isEmpty()) throw new IllegalStateException("Empty feed: "+s.sourceId());
            var item=items.getFirst(); String body=item.body();
            if(!s.bodySelector().isBlank()) {
                robots.requireAllowed(item.url(),s.allowedHosts());
                body=OfficialResearch.body(org.jsoup.Jsoup.parse(new String(http.get(item.url(),s.allowedHosts()),java.nio.charset.StandardCharsets.UTF_8)),s.bodySelector());
            }
            if(body.length()<120) throw new IllegalStateException("No usable body: "+s.sourceId());
            System.out.println(s.sourceId()+": items="+items.size()+" bodyChars="+body.length()+" url="+item.url());
        }
    }
}
