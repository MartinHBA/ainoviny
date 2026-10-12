package sk.verejnydohlad;

import sk.verejnydohlad.Models.*;

public final class PublicApi {
    public static final String DISCLOSURE="Článok bol pripravený s pomocou umelej inteligencie a pred zverejnením schválený ľudským redaktorom. Kontrola súladu so zdrojmi nie je nezávislým overením tvrdení ich vydavateľov.";
    private final Store store;
    public PublicApi(Store store) { this.store=store; }
    public Page list(String size,String cursor,String category) {
        int n=size==null?12:Integer.parseInt(size); if(n<1 || n>30) throw new IllegalArgumentException("Page size must be 1..30");
        Validation.cursor(cursor); if(category!=null && !Validation.CATEGORIES.contains(category)) throw new IllegalArgumentException("Unknown category");
        return store.published(n,cursor,category);
    }
    public Detail detail(String slug) {
        Validation.slug(slug); var a=store.bySlug(slug);
        if(a==null || a.status()!=Status.PUBLISHED || a.reviewedAt()==null || a.approvedHash()==null) return null;
        return new Detail(a.id(),a.slug(),a.title(),a.perex(),a.category(),a.publishedAt(),store.blob("articles",a.blobPath()),a.sourceUrls(),DISCLOSURE);
    }
    public Edition latest() { return store.latest(); }
}
