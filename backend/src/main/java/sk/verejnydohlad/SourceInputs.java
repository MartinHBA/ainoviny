package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

/** Recover primary inputs from the stable topic ID, without confusing archived context with a news topic. */
final class SourceInputs {
    static final String CORRECTION_POLICY="citation-correction-v1";
    static String topicId(List<String> ids) { return Normalize.hash(ids.stream().sorted().reduce((a,b) -> a+"|"+b).orElseThrow()); }
    static String correctionId(String parentId) { return Normalize.hash(parentId+"|"+CORRECTION_POLICY); }
    static List<String> seeds(Article article) {
        var ids=article.sourceIds();
        if(article.editionRevision()!=null)for(String id:ids) {
            String root=Normalize.hash("edition|"+article.editionRevision()+"|"+Normalize.hash(id+"|"+Newsroom.POLICY));
            String revised=Normalize.hash("edition|"+article.editionRevision()+"|"+Newsroom.revisionId(root));
            if(article.id().equals(root) || article.id().equals(revised))return List.of(id);
        }
        if(ids.size()>6) return ids; // Legacy/manual material remains conservative; no exponential scans.
        for(int mask=1;mask<(1<<ids.size());mask++) {
            var subset=new ArrayList<String>();for(int i=0;i<ids.size();i++)if((mask&(1<<i))!=0)subset.add(ids.get(i));
            String topic=topicId(subset);
            if(article.id().equals(topic) || article.id().equals(correctionId(topic)))return List.copyOf(subset);
        }
        return ids;
    }
    static boolean correctionCandidate(Article article) {
        return article.status()==Status.REJECTED && "Automatic checks".equals(article.reviewer()) && !article.sensitive()
            && article.issues().stream().anyMatch(s -> s.startsWith("Numeric claim is not grounded:"))
            && article.id().equals(topicId(seeds(article))); // A correction can never create another correction.
    }
    private SourceInputs() {}
}
