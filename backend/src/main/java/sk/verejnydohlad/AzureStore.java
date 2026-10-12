package sk.verejnydohlad;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.BinaryData;
import com.azure.data.tables.*;
import com.azure.data.tables.models.*;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.*;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import sk.verejnydohlad.Models.*;

public final class AzureStore implements Store {
    private static final String NEWSROOM = "editorial", PRESS = "press";
    private final BlobServiceClient blobs;
    private final TableClient sources, releases, articles;
    public AzureStore(Config config) {
        var bb = new BlobServiceClientBuilder();
        var tb = new TableServiceClientBuilder();
        if (!config.storageConnection().isBlank()) {
            bb.connectionString(config.storageConnection()); tb.connectionString(config.storageConnection());
        } else {
            if (!config.accountName().matches("[a-z0-9]{3,24}")) throw new IllegalArgumentException("STORAGE_ACCOUNT_NAME is required");
            var identity = new DefaultAzureCredentialBuilder().build();
            bb.endpoint("https://" + config.accountName() + ".blob.core.windows.net").credential(identity);
            tb.endpoint("https://" + config.accountName() + ".table.core.windows.net").credential(identity);
        }
        blobs = bb.buildClient(); var tables = tb.buildClient();
        sources = tables.getTableClient("Sources"); releases = tables.getTableClient("PressReleases"); articles = tables.getTableClient("Articles");
    }
    public void initialize(List<Source> registry) {
        for (String name : List.of("sources", "articles", "images")) blobs.getBlobContainerClient(name).createIfNotExists();
        for (TableClient t : List.of(sources, releases, articles)) {
            try { t.createTable(); } catch (TableServiceException e) { if (e.getResponse().getStatusCode() != 409) throw e; }
        }
        for (Source s : registry) {
            var entity = new TableEntity("registry", s.sourceId()).addProperty("payload", Json.write(s))
                .addProperty("sourceId",s.sourceId()).addProperty("name",s.name()).addProperty("baseUrl",s.baseUrl())
                .addProperty("feedUrl",s.feedUrl()).addProperty("sourceType",s.sourceType()).addProperty("enabled",s.enabled());
            sources.upsertEntity(entity);
        }
    }
    public Guard lock() {
        BlobClient blob = blobs.getBlobContainerClient("sources").getBlobClient("_ops/pipeline.lock");
        try { blob.upload(BinaryData.fromString("pipeline"), false); }
        catch (BlobStorageException e) { if (e.getStatusCode() != 409) throw e; }
        var lease = new BlobLeaseClientBuilder().blobClient(blob).buildClient();
        lease.acquireLease(60);
        AtomicBoolean healthy = new AtomicBoolean(true);
        var renewer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "storage-lease-renewal"); t.setDaemon(true); return t; });
        renewer.scheduleAtFixedRate(() -> { try { lease.renewLease(); } catch (Exception e) { healthy.set(false); } }, 20, 20, TimeUnit.SECONDS);
        return new Guard() {
            public void check() { if (!healthy.get()) throw new IllegalStateException("Pipeline lease lost; stopping writes"); }
            public void close() { renewer.shutdownNow(); try { lease.releaseLease(); } catch (Exception ignored) {} }
        };
    }
    public void putBlob(String container, String path, String content) {
        BlobClient blob = blobs.getBlobContainerClient(container).getBlobClient(path);
        try {
            blob.upload(BinaryData.fromString(content), false);
            blob.setHttpHeaders(new BlobHttpHeaders().setContentType(container.equals("articles") ? "text/markdown; charset=utf-8" : "application/json; charset=utf-8"));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() != 409 || !blob.downloadContent().toString().equals(content)) throw e;
        }
    }
    public String blob(String container, String path) { return blobs.getBlobContainerClient(container).getBlobClient(path).downloadContent().toString(); }
    public String optionalBlob(String container, String path) {
        try { return blob(container,path); } catch (BlobStorageException e) { if(e.getStatusCode()==404) return null; throw e; }
    }
    public boolean knownRelease(String id) { return get(releases,PRESS,"url|"+id)!=null; }
    public boolean collect(Release r) {
        putBlob("sources", r.blobPath(), Json.write(r));
        var entity = new TableEntity(PRESS, "url|" + r.id()).addProperty("payload", Json.write(r))
            .addProperty("processingStatus", "PENDING").addProperty("sourceId", r.sourceId()).addProperty("contentHash", r.contentHash())
            .addProperty("publishedAt", r.publishedAt().toString()).addProperty("collectedAt", r.collectedAt().toString());
        // Remove the body from table data: release metadata only, original content lives in Blob.
        entity.addProperty("payload", Json.write(new Release(r.id(), r.sourceId(), r.organization(), r.originalUrl(), r.publishedAt(), r.collectedAt(), r.title(), "", r.contentHash(), r.blobPath())));
        try {
            releases.submitTransaction(List.of(new TableTransactionAction(TableTransactionActionType.CREATE, entity),
                new TableTransactionAction(TableTransactionActionType.CREATE, new TableEntity(PRESS, "hash|" + r.contentHash()).addProperty("releaseId", r.id()))));
            return true;
        } catch (TableServiceException e) { if (conflict(e)) return false; throw e; }
    }
    public List<Release> pending(int limit) {
        var result = new ArrayList<Release>();
        for (TableEntity e : query(releases, "PartitionKey eq 'press' and RowKey ge 'url|' and RowKey lt 'url}' and (processingStatus eq 'PENDING' or processingStatus eq 'ANALYZED')", limit)) {
            Release metadata = Json.read((String)e.getProperty("payload"), Release.class);
            result.add(Json.read(blob("sources", metadata.blobPath()), Release.class));
            if (result.size() == limit) break;
        }
        result.sort(Comparator.comparing(Release::publishedAt).reversed()); return result;
    }
    public Release release(String id) {
        var e = get(releases, PRESS, "url|" + id);
        if (e == null) throw new IllegalArgumentException("Source material not found");
        var metadata = Json.read((String)e.getProperty("payload"), Release.class);
        return Json.read(blob("sources", metadata.blobPath()), Release.class);
    }
    public void releaseStatus(Release r, String status, Analysis a) {
        var e = new TableEntity(PRESS, "url|" + r.id()).addProperty("processingStatus", status);
        if (a != null) e.addProperty("analysis", Json.write(a));
        releases.updateEntity(e, TableEntityUpdateMode.MERGE);
    }
    public Analysis analysis(Release r) {
        var e = get(releases, PRESS, "url|" + r.id());
        return e == null || e.getProperty("analysis") == null ? null : Json.read((String)e.getProperty("analysis"), Analysis.class);
    }
    private TableEntity articleEntity(Article a, String key) {
        if(Json.write(a).length()>30000) throw new IllegalArgumentException("Article metadata exceeds Table property limit");
        return new TableEntity(NEWSROOM, key).addProperty("payload", Json.write(a)).addProperty("status", a.status().name())
            .addProperty("category", a.category()).addProperty("editionDate", a.editionDate())
            .addProperty("articleId",a.id()).addProperty("slug",a.slug()).addProperty("title",a.title()).addProperty("perex",a.perex())
            .addProperty("createdAt",a.createdAt().toString()).addProperty("publishedAt",a.publishedAt()==null?"":a.publishedAt().toString())
            .addProperty("blobPath",a.blobPath()).addProperty("sourceIds",Json.write(a.sourceIds())).addProperty("sourceUrls",Json.write(a.sourceUrls()));
    }
    public Versioned<Article> article(String id) {
        var e = get(articles, NEWSROOM, "id|" + id);
        return e == null ? null : new Versioned<>(Json.read((String)e.getProperty("payload"), Article.class), e.getETag());
    }
    public boolean createArticle(Article a) {
        try { articles.createEntity(articleEntity(a, "id|" + a.id())); return true; }
        catch (TableServiceException e) { if (e.getResponse().getStatusCode() == 409) return false; throw e; }
    }
    public void updateArticle(Article a, String version) {
        var e = articleEntity(a, "id|" + a.id()); e.addProperty("odata.etag", version);
        articles.updateEntityWithResponse(e, TableEntityUpdateMode.REPLACE, true, Duration.ofSeconds(20), null);
    }
    public void publish(Article a, String version) {
        var canonical = articleEntity(a, "id|" + a.id()); canonical.addProperty("odata.etag", version);
        String key = publicKey(a);
        articles.submitTransaction(List.of(new TableTransactionAction(TableTransactionActionType.UPDATE_REPLACE, canonical, true),
            new TableTransactionAction(TableTransactionActionType.CREATE, articleEntity(a, key)),
            new TableTransactionAction(TableTransactionActionType.CREATE, articleEntity(a, "slug|" + a.slug()))));
    }
    private static String publicKey(Article a) { return "pub|" + String.format(Locale.ROOT, "%019d", Long.MAX_VALUE - a.publishedAt().toEpochMilli()) + "|" + a.id(); }
    public List<Article> editorial() {
        var result = new ArrayList<Article>();
        for (var e : query(articles, "PartitionKey eq 'editorial' and RowKey ge 'id|' and RowKey lt 'id}'", 100)) result.add(Json.read((String)e.getProperty("payload"), Article.class));
        return result;
    }
    public Page published(int size, String cursor, String category) {
        String start = cursor == null ? "RowKey ge 'pub|'" : "RowKey gt '" + cursor + "'";
        String filter = "PartitionKey eq 'editorial' and " + start + " and RowKey lt 'pub}' and status eq 'PUBLISHED'";
        if (category != null) filter += " and category eq '" + category.replace("'", "''") + "'";
        var result = new ArrayList<Summary>(); String last = null;
        for (var e : query(articles, filter, size + 1)) {
            if (result.size() == size) return new Page(result, last);
            result.add(Summary.of(Json.read((String)e.getProperty("payload"), Article.class))); last = e.getRowKey();
        }
        return new Page(result, null);
    }
    public Article bySlug(String slug) {
        var e = get(articles, NEWSROOM, "slug|" + slug);
        if (e == null || !"PUBLISHED".equals(e.getProperty("status"))) return null;
        return Json.read((String)e.getProperty("payload"), Article.class);
    }
    public Edition latest() {
        var first = query(articles, "PartitionKey eq 'editorial' and RowKey ge 'pub|' and RowKey lt 'pub}' and status eq 'PUBLISHED'", 1).iterator();
        if (!first.hasNext()) return new Edition(null, null, List.of());
        Article latest = Json.read((String)first.next().getProperty("payload"), Article.class);
        var edition = new ArrayList<Article>();
        for (var e : query(articles, "PartitionKey eq 'editorial' and RowKey ge 'pub|' and RowKey lt 'pub}' and status eq 'PUBLISHED' and editionDate eq '" + latest.editionDate() + "'", 3)) {
            edition.add(Json.read((String)e.getProperty("payload"), Article.class));
        }
        edition.sort(Comparator.comparingInt(Article::editorialOrder));
        return new Edition(latest.editionDate(), Summary.of(edition.getFirst()), edition.stream().skip(1).map(Summary::of).toList());
    }
    public Budget budget(String date) { var e = get(sources, "ops", "budget|" + date); return e == null ? new Budget(0, List.of()) : Json.read((String)e.getProperty("payload"), Budget.class); }
    private boolean changeBudget(String date, java.util.function.Function<Budget,Budget> change) {
        for (int attempt = 0; attempt < 5; attempt++) {
            var old = get(sources, "ops", "budget|" + date);
            Budget b = old == null ? new Budget(0, List.of()) : Json.read((String)old.getProperty("payload"), Budget.class);
            Budget next = change.apply(b); if (next == null) return false;
            var e = new TableEntity("ops", "budget|" + date).addProperty("payload", Json.write(next));
            try {
                if (old == null) sources.createEntity(e);
                else { e.addProperty("odata.etag", old.getETag()); sources.updateEntityWithResponse(e, TableEntityUpdateMode.REPLACE, true, Duration.ofSeconds(20), null); }
                return true;
            } catch (TableServiceException conflict) {
                if (!Set.of(409,412).contains(conflict.getResponse().getStatusCode())) throw conflict;
            }
        }
        throw new IllegalStateException("Daily budget contention");
    }
    public boolean reserveCall(String date, int max) { return changeBudget(date, b -> b.calls() >= max ? null : new Budget(b.calls() + 1, b.articleIds())); }
    public boolean reserveArticle(String date, String id, int max) {
        return changeBudget(date, b -> { if (b.articleIds().contains(id)) return b; if (b.articleIds().size() >= max) return null;
            var ids = new ArrayList<>(b.articleIds()); ids.add(id); return new Budget(b.calls(), ids); });
    }
    public void sourceChecked(Source s, Instant at) {
        // Provisioned tables can be empty: a timer must not require a human CLI init before its first run.
        sources.upsertEntityWithResponse(new TableEntity("registry",s.sourceId()).addProperty("payload",Json.write(s))
            .addProperty("sourceId",s.sourceId()).addProperty("name",s.name()).addProperty("baseUrl",s.baseUrl())
            .addProperty("feedUrl",s.feedUrl()).addProperty("sourceType",s.sourceType()).addProperty("enabled",s.enabled())
            .addProperty("lastCheckedAt",at.toString()),TableEntityUpdateMode.MERGE,Duration.ofSeconds(20),null);
    }
    public void run(RunResult r) { sources.createEntity(new TableEntity("ops", "run|" + r.startedAt() + "|" + UUID.randomUUID()).addProperty("payload", Json.write(r))); }
    private static PagedIterable<TableEntity> query(TableClient t, String filter, int pageSize) {
        return t.listEntities(new ListEntitiesOptions().setFilter(filter).setTop(pageSize), Duration.ofSeconds(30), null);
    }
    private static TableEntity get(TableClient t, String partition, String row) {
        try { return t.getEntity(partition, row); }
        catch (TableServiceException e) { if (e.getResponse().getStatusCode() == 404) return null; throw e; }
    }
    private static boolean conflict(TableServiceException e) {
        return e.getResponse()!=null && e.getResponse().getStatusCode()==409
            || e.getValue()!=null && TableErrorCode.ENTITY_ALREADY_EXISTS.equals(e.getValue().getErrorCode());
    }
}
