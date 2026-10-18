package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

public final class PublicApi {
    public static final String DISCLOSURE="Článok bol pripravený s pomocou umelej inteligencie a pred zverejnením schválený ľudským redaktorom. Kontrola súladu so zdrojmi nie je nezávislým overením tvrdení ich vydavateľov.";
    public static final String AUTOMATIC_DISCLOSURE="Článok pripravila umelá inteligencia a pred automatickým zverejnením ho porovnal s pôvodnými zdrojmi samostatný AI editor. Text neprešiel ľudskou redakčnou kontrolou. Kontrola súladu so zdrojmi nie je nezávislým overením tvrdení ich vydavateľov.";
    private final Store store;
    public PublicApi(Store store) { this.store=store; }
    public Page list(String size,String cursor,String category) {
        int n=size==null?12:Integer.parseInt(size); if(n<1 || n>30) throw new IllegalArgumentException("Page size must be 1..30");
        Validation.cursor(cursor); if(category!=null && !Validation.CATEGORIES.contains(category)) throw new IllegalArgumentException("Unknown category");
        return store.published(n,cursor,category);
    }
    public Detail detail(String slug) {
        Validation.slug(slug); var a=store.bySlug(slug);
        if(a==null || a.status()!=Status.PUBLISHED || a.reviewedAt()==null || a.reviewer()==null || a.approvedHash()==null) return null;
        return new Detail(a.id(),a.slug(),a.title(),a.perex(),a.category(),a.publishedAt(),store.blob("articles",a.blobPath()),citedSources(a),AutomaticEditor.isAutomatic(a)?AUTOMATIC_DISCLOSURE:DISCLOSURE);
    }
    static List<String> citedSources(Article a) {
        if(a.sourceIds().size()!=a.sourceUrls().size() || a.claims().isEmpty())throw new IllegalStateException("Invalid published citation metadata");
        var urls=new LinkedHashMap<String,String>();
        for(int i=0;i<a.sourceIds().size();i++) {
            String previous=urls.putIfAbsent(a.sourceIds().get(i),a.sourceUrls().get(i));
            if(previous!=null && !previous.equals(a.sourceUrls().get(i)))throw new IllegalStateException("Conflicting published citation metadata");
        }
        var cited=new HashSet<String>();a.claims().forEach(c->cited.add(c.sourceId()));
        if(!urls.keySet().containsAll(cited))throw new IllegalStateException("Missing published citation source");
        return urls.entrySet().stream().filter(e->cited.contains(e.getKey())).map(Map.Entry::getValue).distinct().toList();
    }
    public Edition latest() { return store.latest(); }
}
