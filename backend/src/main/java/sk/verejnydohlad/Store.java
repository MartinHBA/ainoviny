package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

/** CAS versions are opaque. All public indexes and the canonical article commit atomically. */
public interface Store {
    record Versioned<T>(T value, String version) {}
    interface Guard extends AutoCloseable { void check(); @Override void close(); }
    void initialize(List<Source> sources);
    Guard lock();
    void putBlob(String container, String path, String content);
    String blob(String container, String path);
    String optionalBlob(String container, String path);
    boolean knownRelease(String id);
    boolean collect(Release release);
    Release release(String id);
    List<Release> pending(int limit);
    List<Release> recent(java.time.Instant since, int limit);
    void releaseStatus(Release release, String status, Analysis analysis);
    String releaseStatus(Release release);
    Analysis analysis(Release release);
    Versioned<Article> article(String id);
    boolean createArticle(Article article);
    void updateArticle(Article article, String version);
    void publish(Article article, String version);
    default void publish(Article article,String version,String scope) {
        if(!scope.equals("scheduled"))throw new UnsupportedOperationException("Store does not support independent manual publication");
        publish(article,version);
    }
    default String publicationScope(String articleId){return "scheduled";}
    List<Article> editorial();
    Page published(int size, String cursor, String category);
    Article bySlug(String slug);
    Edition latest();
    Budget budget(String date);
    boolean reserveCall(String date, int max);
    boolean reserveArticle(String date, String id, int max);
    void sourceChecked(Source source, java.time.Instant at);
    void run(RunResult result);
    default void run(RunResult result,String scope){run(result);}
    default String operational(String key){return null;}
    default void operational(String key,String payload){throw new UnsupportedOperationException("Newsroom state unavailable");}
    default List<StoryOrder> orders(){return List.of();}
    default List<StoryOrder> orders(String prefix){if(prefix.isEmpty())return orders();throw new UnsupportedOperationException("Scoped orders unavailable");}
    default String workId(String id){return id;}
    default String revisionId(){return null;}
    default EditionRevision revision(String id){throw new UnsupportedOperationException("Edition revisions unavailable");}
    default EditionRevision activeRevision(String date){return null;}
    default void revision(EditionRevision revision){throw new UnsupportedOperationException("Edition revisions unavailable");}
    default void stage(Article article,String version,String scope){throw new UnsupportedOperationException("Private edition staging unavailable");}
    default void activate(EditionRevision revision){throw new UnsupportedOperationException("Atomic edition activation unavailable");}
}
