package sk.verejnydohlad;

import java.time.Instant;
import java.util.List;
import sk.verejnydohlad.Models.*;

interface ResearchLookup {
    record Result(List<Release> documents,List<String> gaps) {}
    Result find(ResearchPlan plan,Draft concept,List<Release> seeds,Store store,Store.Guard guard,Instant deadline);
    static ResearchLookup archiveOnly() {
        return (plan,concept,seeds,store,guard,deadline) -> new Result(Research.related(seeds,store.recent(Instant.EPOCH,100)),
            List.of("Offline režim: verejné weby neboli prehľadané; dostupný bol iba uložený archív."));
    }
}
