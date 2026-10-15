package sk.verejnydohlad;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import sk.verejnydohlad.Models.*;

/** Rotate the official catalog before a concept exists. Discovered documents remain reusable context. */
final class OfficialDiscovery implements Newsroom.Discovery {
    private final OfficialResearch.Fetch testFetch;
    OfficialDiscovery(){this(null);}
    OfficialDiscovery(OfficialResearch.Fetch fetch){testFetch=fetch;}
    static final String TERMS="rozpočet výdavky dane prognóza analýza hodnotenie investícia rozhodnutie ceny energie mzdy dôchodky zdravotníctvo nezamestnanosť reforma kontrola eurofondy financovanie";
    public Result scan(Store store,Store.Guard guard,Instant limit) {
        Instant started=Instant.now();
        var sites=ResearchRegistry.SITES.stream().filter(s -> !s.mode().equals("UNSUPPORTED")).toList();
        int cursor=Optional.ofNullable(store.operational("discovery-cursor")).map(Integer::parseInt).orElse(0);
        Instant end=Instant.now().plusSeconds(90);if(end.isAfter(limit))end=limit;
        Instant deadline=end;var requests=new AtomicInteger();
        var http=new SafeHttp(1,8,() -> {
            guard.check();if(requests.incrementAndGet()>24 || Instant.now().plusSeconds(9).isAfter(deadline))throw new IllegalStateException("Discovery budget reached");
            if(requests.get()>1)try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
        });
        var robots=new Robots(http);OfficialResearch.Fetch fetch=testFetch!=null?testFetch:(u,h)->{robots.requireAllowed(u,h);return http.get(u,h);};
        var found=new LinkedHashMap<String,Release>();var gaps=new ArrayList<String>();int checked=0;
        for(int offset=0;offset<5 && offset<sites.size();offset++) {
            if(Instant.now().isAfter(end) || found.size()>=6)break;
            var site=sites.get(Math.floorMod(cursor+offset,sites.size()));checked++;
            try {
                List<OfficialResearch.Link> links;
                var feedItems=new HashMap<String,FeedParser.Item>();
                if(Set.of("FEED","SEARCH_FEED").contains(site.mode())) {
                    String feed=site.mode().equals("SEARCH_FEED")?site.entryUrl()+"feed/":site.entryUrl();
                    var source=new Source(site.id(),site.name(),site.entryUrl(),feed,"RSS",true,"Topic discovery",site.selector(),site.hosts());
                    var items=new FeedParser().parse(fetch.get(feed,site.hosts()),source);
                    items.forEach(i->feedItems.put(i.url(),i));
                    links=items.stream()
                        .filter(i -> !i.publishedAt().isBefore(Instant.now().minus(Duration.ofDays(90))))
                        .map(i -> new OfficialResearch.Link(i.title(),i.url(),1)).toList();
                } else {
                    var page=Jsoup.parse(new String(fetch.get(site.entryUrl(),site.hosts()),StandardCharsets.UTF_8),site.entryUrl());
                    links=OfficialResearch.links(page,site,TERMS);
                }
                int attempts=0,before=found.size();
                for(var link:links) {
                    if(found.size()>=6 || found.size()-before>=2 || Instant.now().isAfter(end) || attempts>=3)break;
                    if(store.knownRelease(Normalize.hash(link.url())))continue;
                    attempts++;
                    var item=feedItems.get(link.url());
                    try {new OfficialResearch(testFetch).retrieve(link,site,TERMS,item==null?Instant.EPOCH:item.publishedAt(),fetch,store,guard,found,gaps,end);}
                    catch(Exception e){
                        if(item!=null && item.body().length()>=120 && item.body().length()<=50000) {
                            OfficialResearch.add(site,item.url(),item.title(),item.body(),item.publishedAt(),store,guard,found);
                            gaps.add(site.id()+": použitý skutočný text feedu; HTML a prílohy neboli úplne načítané.");
                        }else gaps.add(site.id()+": vybraný dokument sa nepodarilo načítať.");
                    }
                }
            }catch(Exception e){gaps.add(site.id()+": objavovanie nedostupné alebo vyčerpaný limit prístupu.");}
        }
        guard.check();store.operational("discovery-cursor",Integer.toString(Math.floorMod(cursor+checked,sites.size())));
        gaps.add("Nepodporované dátové registre neboli prehľadané; ide o obmedzený rotačný prieskum katalógu.");
        return new Result((int)found.values().stream().filter(r->!r.collectedAt().isBefore(started)).count(),checked,List.copyOf(gaps));
    }
}
