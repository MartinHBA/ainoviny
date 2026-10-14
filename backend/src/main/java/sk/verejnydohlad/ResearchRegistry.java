package sk.verejnydohlad;

import java.net.URI;
import java.util.*;
import sk.verejnydohlad.Models.*;

/** Versioned editorial allowlist; model output selects IDs, never arbitrary URLs. */
final class ResearchRegistry {
    record Site(String id,String name,String group,String mode,String entryUrl,List<String> hosts,String selector,String scope) {}
    static final List<Site> SITES=load();
    private static List<Site> load() {
        try(var stream=ResearchRegistry.class.getResourceAsStream("/research-sites.json")) {
            if(stream==null)throw new IllegalStateException("Missing research registry");
            var sites=List.of(Json.MAPPER.readValue(stream,Site[].class));var ids=new HashSet<String>();
            for(var s:sites) {
                var u=URI.create(s.entryUrl());
                if(!ids.add(s.id()) || !Set.of("SEARCH_FEED","FEED","HTML","UNSUPPORTED").contains(s.mode()) ||
                    !"https".equals(u.getScheme()) || !s.hosts().contains(u.getHost()) || u.getUserInfo()!=null)
                    throw new IllegalStateException("Invalid research site");
            }
            return sites;
        } catch(Exception e){throw new IllegalStateException("Invalid research registry",e);}
    }
    static Site site(String id) { return SITES.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown research site")); }
    static String group(String id) { return SITES.stream().filter(s -> s.id().equals(id)).map(Site::group).findFirst().orElse(id); }
    static void validate(ResearchPlan p) {
        if(p==null || p.question()==null || p.question().isBlank() || p.question().length()>600 || p.queries()==null || p.queries().isEmpty() || p.queries().size()>2 ||
            p.sourceIds()==null || p.sourceIds().isEmpty() || p.sourceIds().size()>4 || new HashSet<>(p.sourceIds()).size()!=p.sourceIds().size() ||
            p.queries().stream().anyMatch(q -> q==null || q.isBlank() || q.length()>100 || q.contains("://") || q.contains("\n")))
            throw new IllegalArgumentException("Invalid research plan");
        p.sourceIds().forEach(ResearchRegistry::site);
    }
    private ResearchRegistry() {}
}
