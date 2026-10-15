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
                        body=OfficialResearch.body(page,site);var parsed=OfficialResearch.date(page);if(!parsed.equals(Instant.EPOCH))date=parsed;
                    }
                }catch(Exception e){throw new IllegalStateException("Current original cannot be extracted",e);}
                if(body.length()<120 || body.length()>50000)throw new IllegalStateException("Current original text outside bounds");
                result.add(new Release(source.id(),source.sourceId(),source.organization(),source.originalUrl(),date,Instant.now(),title,body,Normalize.hash(body),source.blobPath()));
            }
            return List.copyOf(result);
        };
    }
}
