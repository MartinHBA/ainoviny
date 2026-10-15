package sk.verejnydohlad;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import sk.verejnydohlad.Models.*;

/** Fetch full current originals before a stored article can leave the private reserve. */
interface PublicationCheck {
    List<Release> refresh(List<Release> originals,Store.Guard guard,Instant deadline);
    static PublicationCheck offline(){return (originals,guard,deadline)->originals;}
    static PublicationCheck official(){return official(null);}
    static PublicationCheck official(OfficialResearch.Fetch testFetch) {
        return (originals,guard,limit) -> {
            Instant end=Instant.now().plusSeconds(65);if(end.isAfter(limit))end=limit;Instant deadline=end;
            var requests=new AtomicInteger();
            var http=new SafeHttp(1,8,() -> {
                guard.check();if(requests.incrementAndGet()>16 || Instant.now().plusSeconds(9).isAfter(deadline))throw new IllegalStateException("Freshness budget reached");
                if(requests.get()>1)try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
            });
            var robots=new Robots(http);OfficialResearch.Fetch fetch=testFetch!=null?testFetch:(u,h)->{robots.requireAllowed(u,h);return http.get(u,h);};
            var feeds=new HashMap<String,List<FeedParser.Item>>();
            var result=new ArrayList<Release>();
            for(var source:originals) {
                guard.check();var site=ResearchRegistry.site(source.sourceId());
                if(site.mode().equals("UNSUPPORTED") || !OfficialResearch.allowed(source.originalUrl(),site) || !OfficialResearch.inScope(source.originalUrl(),site))throw new IllegalStateException("Source cannot be refreshed");
                byte[] bytes=fetch.get(source.originalUrl(),site.hosts());String title=source.title(),body;Instant date=source.publishedAt();
                try {
                    if(OfficialResearch.isPdf(bytes))body=OfficialResearch.pdf(bytes);
                    else {
                        var page=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),source.originalUrl());
                        String h1=page.select("h1").text();if(!h1.isBlank())title=h1;
                        try {body=OfficialResearch.body(page,site);}
                        catch(IllegalArgumentException missingSelector){body="";}
                        var parsed=OfficialResearch.date(page);if(!parsed.equals(Instant.EPOCH))date=parsed;
                        if(body.length()<120 && Set.of("FEED","SEARCH_FEED").contains(site.mode())) {
                            // Re-fetch today's official feed, never use the archived body as a freshness fallback.
                            // Match the exact canonical article URL; an unrelated/newer item is not evidence.
                            var items=feeds.get(site.id());
                            if(items==null) {
                                String url=site.mode().equals("SEARCH_FEED")?site.entryUrl()+"feed/":site.entryUrl();
                                var feed=new Source(site.id(),site.name(),site.entryUrl(),url,"RSS",true,"Current original feed","",site.hosts());
                                items=new FeedParser().parse(fetch.get(url,site.hosts()),feed);feeds.put(site.id(),items);
                            }
                            var current=items.stream().filter(i->Normalize.canonical(i.url()).equals(Normalize.canonical(source.originalUrl())) && i.body().length()>=120 && i.body().length()<=50000).findFirst()
                                .orElseThrow(()->new IllegalStateException("Current original absent from official feed"));
                            title=current.title();body=current.body();date=current.publishedAt();
                        }
                    }
                }catch(Exception e){throw new IllegalStateException("Current original cannot be extracted",e);}
                if(body.length()<120 || body.length()>50000)throw new IllegalStateException("Current original text outside bounds");
                result.add(new Release(source.id(),source.sourceId(),source.organization(),source.originalUrl(),date,Instant.now(),title,body,Normalize.hash(body),source.blobPath()));
            }
            return List.copyOf(result);
        };
    }
}
