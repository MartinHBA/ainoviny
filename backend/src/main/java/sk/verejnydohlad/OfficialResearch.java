package sk.verejnydohlad;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import sk.verejnydohlad.Models.*;

/** Bounded official-site discovery, not an open-web browsing loop. No model-generated URLs. */
final class OfficialResearch implements ResearchLookup {
    interface Fetch {byte[] get(String url,List<String> hosts);}
    private final Fetch testFetch;
    OfficialResearch(){this(null);}
    OfficialResearch(Fetch fetch){testFetch=fetch;}
    record Link(String title,String url,int score) {}
    public Result find(ResearchPlan plan,Draft concept,List<Release> seeds,Store store,Store.Guard guard,Instant pipelineDeadline) {
        ResearchRegistry.validate(plan);
        Instant deadline=Instant.now().plusSeconds(65);if(deadline.isAfter(pipelineDeadline))deadline=pipelineDeadline;
        Instant end=deadline;var requests=new AtomicInteger();
        var http=new SafeHttp(1,8,() -> {
            guard.check();if(requests.incrementAndGet()>16 || Instant.now().plusSeconds(9).isAfter(end))throw new IllegalStateException("Research retrieval budget reached");
            if(requests.get()>1)try {Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
        });
        var robots=new Robots(http);
        Fetch fetch=testFetch!=null?testFetch:(url,hosts) -> {robots.requireAllowed(url,hosts);return http.get(url,hosts);};
        var gaps=new ArrayList<String>();var found=new LinkedHashMap<String,Release>();seeds.forEach(s -> found.put(s.id(),s));
        var archive=Research.related(seeds,store.recent(Instant.now().minus(Duration.ofDays(90)),100));
        String terms=String.join(" ",plan.queries());
        for(String id:plan.sourceIds()) {
            var site=ResearchRegistry.site(id);
            if(site.mode().equals("UNSUPPORTED")) {gaps.add(site.name()+": konektor zatiaľ nepodporuje tento zdroj. "+site.scope());continue;}
            if(Instant.now().isAfter(end) || found.size()>=6) {gaps.add(site.name()+": nedohľadané pre limit času alebo dokumentov.");continue;}
            int before=found.size();
            try {
                if(site.mode().equals("FEED") || site.mode().equals("SEARCH_FEED")) {
                    var items=new ArrayList<FeedParser.Item>();
                    for(String query:site.mode().equals("SEARCH_FEED")?plan.queries():List.of("")) {
                        String url=site.mode().equals("SEARCH_FEED")?site.entryUrl()+"?s="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&feed=rss2":site.entryUrl();
                        var source=new Source(site.id(),site.name(),site.entryUrl(),url,"RSS",true,"Research discovery",site.selector(),site.hosts());
                        try {items.addAll(new FeedParser().parse(fetch.get(url,site.hosts()),source));}
                        catch(Exception e){gaps.add(site.name()+": jedna vyhľadávacia fráza alebo feed nebol dostupný.");}
                    }
                    var selected=items.stream().filter(i -> score(terms,i.title()+" "+i.body())>0)
                        .sorted(Comparator.<FeedParser.Item>comparingInt(i -> score(terms,i.title()+" "+i.body())).reversed()
                            .thenComparing(FeedParser.Item::publishedAt,Comparator.reverseOrder())).limit(2).toList();
                    for(var item:selected) {
                        if(found.size()>=6 || Instant.now().isAfter(end))break;
                        String documentId=Normalize.hash(item.url());
                        if(store.knownRelease(documentId)) {
                            found.putIfAbsent(documentId,store.release(documentId));
                            try {
                                var page=Jsoup.parse(new String(fetch.get(item.url(),site.hosts()),StandardCharsets.UTF_8),item.url());
                                attachments(page,site,terms,item.publishedAt(),fetch,store,guard,found,gaps,end);
                            }catch(Exception e){gaps.add(site.name()+": použitý uložený originál; nové prílohy sa nepodarilo načítať.");}
                            continue;
                        }
                        try {
                            var page=Jsoup.parse(new String(fetch.get(item.url(),site.hosts()),StandardCharsets.UTF_8),item.url());
                            String body=item.body();
                            try {String full=body(page,site);if(full.length()>=120)body=full;}
                            catch(IllegalArgumentException e){gaps.add(site.name()+": obsah článku pochádza z feedu; HTML selektor neposkytol spoľahlivý plný text.");}
                            add(site,item.url(),item.title(),body,item.publishedAt(),store,guard,found);
                            attachments(page,site,terms,item.publishedAt(),fetch,store,guard,found,gaps,end);
                        } catch(Exception e) {
                            if(item.body().length()>=120)add(site,item.url(),item.title(),item.body(),item.publishedAt(),store,guard,found);
                            gaps.add(site.name()+": plný dokument alebo prílohy neboli dostupné; rozsah je iba skutočne uložený text.");
                        }
                    }
                } else {
                    var hub=Jsoup.parse(new String(fetch.get(site.entryUrl(),site.hosts()),StandardCharsets.UTF_8),site.entryUrl());
                    for(var link:links(hub,site,terms).stream().limit(2).toList()) {
                        if(found.size()>=6 || Instant.now().isAfter(end))break;
                        try {retrieve(link,site,terms,Instant.EPOCH,fetch,store,guard,found,gaps,end);}
                        catch(Exception e){gaps.add(site.name()+": vybraný dokument sa nepodarilo spoľahlivo načítať.");}
                    }
                }
                if(found.size()==before)gaps.add(site.name()+": v obmedzenom vyhľadávaní sa nenašiel použiteľný súvisiaci dokument.");
            } catch(Exception e) {gaps.add(site.name()+": vyhľadávanie nedostupné alebo vyčerpaný limit prístupu.");}
        }
        // Fill unused space with related cached context, after giving targeted independent sources a chance.
        for(var r:archive)if(found.size()<6 && found.values().stream().noneMatch(s -> s.contentHash().equals(r.contentHash())))found.putIfAbsent(r.id(),r);
        for(var r:found.values())if(r.publishedAt().equals(Instant.EPOCH))gaps.add(r.organization()+": dátum publikovania dokumentu nie je overený ("+r.id().substring(0,12)+").");
        gaps.add("Rešerš bola obmedzená na vybrané oficiálne zdroje a dostupné dokumenty; nejde o úplné pokrytie témy.");
        return new Result(List.copyOf(found.values()),gaps.stream().distinct().limit(16).toList());
    }
    static boolean allowed(String url,ResearchRegistry.Site site) {
        try {var u=URI.create(url);return "https".equals(u.getScheme()) && site.hosts().contains(u.getHost()) && u.getUserInfo()==null && (u.getPort()==-1 || u.getPort()==443);}
        catch(Exception e){return false;}
    }
    static List<Link> links(Document page,ResearchRegistry.Site site,String terms) {
        var links=new LinkedHashMap<String,Link>();
        for(var a:page.select("a[href]")) {
            String url;try{url=URI.create(page.baseUri()).resolve(a.attr("href").strip()).toString();}catch(Exception e){continue;}
            String title=a.text().strip();
            if(!allowed(url,site) || !inScope(url,site) || title.length()<8)continue;
            url=Normalize.canonical(url.split("#",2)[0]);
            if(url.equals(site.entryUrl()) || url.matches(".*\\.(jpg|png|zip|xlsx|xls|docx|doc)(?:\\?.*)?$"))continue;
            int score=score(terms,title);if(score>0)links.putIfAbsent(url,new Link(title,url,score));
        }
        return links.values().stream().sorted(Comparator.comparingInt(Link::score).reversed().thenComparing(Link::url)).toList();
    }
    void retrieve(Link link,ResearchRegistry.Site site,String terms,Instant inheritedDate,Fetch fetch,Store store,Store.Guard guard,
                          Map<String,Release> found,List<String> gaps,Instant deadline) throws Exception {
        if(!allowed(link.url(),site))throw new IllegalArgumentException("Unapproved research URL");
        String id=Normalize.hash(link.url());if(store.knownRelease(id)){found.putIfAbsent(id,store.release(id));return;}
        byte[] bytes=fetch.get(link.url(),site.hosts());
        if(isPdf(bytes)) {add(site,link.url(),link.title(),pdf(bytes),Instant.EPOCH,store,guard,found);return;}
        var page=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),link.url());
        Instant date=date(page);if(date.equals(Instant.EPOCH))date=inheritedDate;
        String title=page.select("h1").text();if(title.isBlank())title=link.title();
        add(site,link.url(),title,body(page,site),date,store,guard,found);
        attachments(page,site,terms,inheritedDate,fetch,store,guard,found,gaps,deadline);
    }
    private void attachments(Document page,ResearchRegistry.Site site,String terms,Instant date,Fetch fetch,Store store,Store.Guard guard,
                             Map<String,Release> found,List<String> gaps,Instant deadline) {
        for(var link:links(page,site,terms+" hodnotenie prognóza rozpočtu komentár správa").stream()
            .filter(l -> l.url().toLowerCase(Locale.ROOT).contains(".pdf")).limit(1).toList()) {
            if(found.size()>=6 || Instant.now().isAfter(deadline))break;
            try {retrieve(link,site,terms,date,fetch,store,guard,found,gaps,deadline);}
            catch(Exception e){gaps.add(site.name()+": PDF príloha nebola spoľahlivo prečítaná.");}
        }
    }
    static void add(ResearchRegistry.Site site,String url,String title,String body,Instant date,Store store,Store.Guard guard,Map<String,Release> found) {
        if(body.length()<120 || body.length()>50000 || title.isBlank())throw new IllegalArgumentException("Invalid research document length");
        String id=Normalize.hash(url),hash=Normalize.hash(body);
        if(found.values().stream().anyMatch(s -> s.contentHash().equals(hash)))return;
        var r=new Release(id,site.id(),site.name(),url,date,Instant.now(),title,body,hash,"research/"+site.id()+"/"+id+"-"+hash+".json");
        guard.check();
        if(store.collect(r)) {store.releaseStatus(r,"CONTEXT",null);found.put(id,r);}
        else if(store.knownRelease(id))found.putIfAbsent(id,store.release(id));
    }
    static String body(Document page,ResearchRegistry.Site site) {
        var selected=page.select(site.selector()).first();if(selected==null)throw new IllegalArgumentException("No reliable research body selector");
        selected=selected.clone();selected.select("nav,header,footer,script,style,noscript,form,.cookie-notice,.related-posts").remove();
        return Normalize.text(selected.outerHtml());
    }
    static boolean inScope(String url,ResearchRegistry.Site site) {
        String path=URI.create(url).getPath().toLowerCase(Locale.ROOT);
        return switch(site.id()) {
            case "uhp-sr" -> path.contains("/uhp/") || path.contains("/hodnota-za-peniaze/");
            case "iep-sr" -> path.contains("/iep/");
            default -> true;
        };
    }
    static Instant date(Document doc) {
        for(String text:List.of(doc.select("meta[property=article:published_time]").attr("content"),doc.select("meta[name=date]").attr("content"),doc.select("time[datetime]").attr("datetime")))
            try {var date=Normalize.date(text);if(!date.isAfter(Instant.now().plusSeconds(3600)))return date;}catch(Exception ignored){}
        return Instant.EPOCH;
    }
    static boolean isPdf(byte[] bytes){return bytes.length>=5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");}
    static String pdf(byte[] bytes) throws Exception {
        try(var doc=Loader.loadPDF(bytes)) {
            if(doc.isEncrypted() || doc.getNumberOfPages()>60)throw new IllegalArgumentException("Unsupported research PDF");
            var result=new StringBuilder();var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            for(int page=1;page<=doc.getNumberOfPages();page++) {
                stripper.setStartPage(page);stripper.setEndPage(page);String text=Normalize.text(stripper.getText(doc));
                if(!text.isBlank())result.append("[PDF strana ").append(page).append("] ").append(text).append(" ");
                if(result.length()>50000)throw new IllegalArgumentException("Research PDF text too large");
            }
            if(result.length()<120)throw new IllegalArgumentException("Research PDF needs OCR");return result.toString().strip();
        }
    }
    static int score(String query,String text) {
        var terms=terms(query);return (int)terms(text).stream().filter(terms::contains).count();
    }
    private static Set<String> terms(String text) {
        String plain=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        var stop=Set.of("sloven","minist","republ","sprava","verejn","podla","vlad");
        var result=new HashSet<String>();for(String word:plain.split("[^a-z0-9]+"))if(word.length()>=4) {
            String term=word.substring(0,Math.min(6,word.length()));if(!stop.contains(term))result.add(term);
        }return result;
    }
}
