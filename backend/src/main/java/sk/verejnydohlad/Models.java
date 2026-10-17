package sk.verejnydohlad;

import java.time.Instant;
import java.util.List;

public final class Models {
    public enum Status { DRAFT, REVIEWED, PUBLISHED, REJECTED }
    public record Source(String sourceId, String name, String baseUrl, String feedUrl, String sourceType,
                         boolean enabled, String notes, String bodySelector, List<String> allowedHosts) {}
    public record Release(String id, String sourceId, String organization, String originalUrl, Instant publishedAt,
                          Instant collectedAt, String title, String body, String contentHash, String blobPath) {}
    public record Analysis(int score, String topicKey, String category, String reason) {}
    public record Claim(String text, String sourceId, String evidence) {}
    public record Draft(String title, String perex, String category, String markdown, List<Claim> claims) {}
    public record GenerationClaim(String text, String sourceId, String evidenceId) {}
    public record Generation(GenerationClaim title, GenerationClaim perex, String category, List<GenerationClaim> paragraphs) {}
    public record EvidenceRef(String sourceId,String evidenceId) {}
    public record ResearchedClaim(String text,List<EvidenceRef> citations) {}
    public record ResearchedGeneration(ResearchedClaim title,ResearchedClaim perex,String category,List<ResearchedClaim> paragraphs) {}
    public record ResearchPlan(String question,List<String> queries,List<String> sourceIds) {}
    public record ResearchBrief(boolean viable, boolean sensitive, String question, List<GenerationClaim> findings,
                                List<String> limitations, List<String> leads) {}
    public record Verification(boolean passed, boolean sensitive, List<String> issues) {}
    public record EditorDecision(boolean approved, boolean sensitive, List<String> issues, String reason) {}
    public record StoryPitch(String primaryId,List<String> sourceIds,String question,String kind,int score,String reason) {}
    public record StoryOrder(String articleId,StoryPitch pitch,String parentId,Instant plannedAt,Instant expiresAt,List<String> instructions) {}
    public record RevisionFeedback(String action,List<String> instructions,String reason) {}
    public record EditionItem(String id,String title,String perex,String category,String markdown,List<String> sourceIds) {}
    public record EditionGroup(String action,List<String> articleIds,String publishedArticleId,List<String> instructions,String reason) {}
    public record EditionPlan(List<EditionGroup> groups) {}
    public record Article(String id, String slug, String title, String perex, String category, Instant createdAt,
                          Instant generatedAt, Instant publishedAt, Status status, String blobPath,
                          List<String> sourceIds, List<String> sourceUrls, String aiModel,
                          Instant reviewedAt, String reviewer, String approvedHash,
                          String contentHash, boolean factCheckPassed, boolean sensitive,
                          List<String> issues, List<Claim> claims, String phase, int editorialOrder, String editionDate) {
        public Article revise(Draft d, String path, String hash, Verification v, Instant now) {
            return new Article(id, slug, d.title(), d.perex(), d.category(), createdAt, now, null, Status.DRAFT,
                path, sourceIds, sourceUrls, aiModel, null, null, null, hash, v.passed(), v.sensitive(),
                v.issues(), d.claims(), "COMPLETE", editorialOrder, editionDate);
        }
        public Article state(Status next, Instant reviewed, String who, String approval, Instant published, String path) {
            return new Article(id, slug, title, perex, category, createdAt, generatedAt, published, next, path,
                sourceIds, sourceUrls, aiModel, reviewed, who, approval, contentHash, factCheckPassed, sensitive,
                issues, claims, phase, editorialOrder, editionDate);
        }
    }
    public static Article withEdition(Article a,String date) {
        return new Article(a.id(),a.slug(),a.title(),a.perex(),a.category(),a.createdAt(),a.generatedAt(),a.publishedAt(),a.status(),a.blobPath(),a.sourceIds(),a.sourceUrls(),
            a.aiModel(),a.reviewedAt(),a.reviewer(),a.approvedHash(),a.contentHash(),a.factCheckPassed(),a.sensitive(),a.issues(),a.claims(),a.phase(),a.editorialOrder(),date);
    }
    public record Summary(String id, String slug, String title, String perex, String category, Instant publishedAt) {
        public static Summary of(Article a) { return new Summary(a.id(), a.slug(), a.title(), a.perex(), a.category(), a.publishedAt()); }
    }
    public record Page(List<Summary> articles, String nextCursor) {}
    public record Detail(String id, String slug, String headline, String perex, String category, Instant publishedAt,
                         String markdown, List<String> originalSources, String aiDisclosure) {}
    public record Edition(String date, Summary featured, List<Summary> articles) {}
    public record Budget(int calls, List<String> articleIds) {}
    public record RunResult(Instant startedAt, Instant completedAt, int collected, int drafts, int published, int rejected, List<String> errors) {}
    private Models() {}
}
