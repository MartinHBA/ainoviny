package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit public-network check, no Azure credentials, AI calls or production Storage writes. */
@EnabledIfSystemProperty(named="research.integration",matches="true")
class OfficialResearchLiveIT {
    @Test void officialBudgetSearchRetrievesRRZAndIFPOriginalsThroughProductionSafetyRules() {
        var store=new MemoryStore();var seed=Fixtures.release("public-connectivity-only");store.collect(seed);
        try(var guard=store.lock()) {
            var result=new OfficialResearch().find(new ResearchPlan("Ako hodnotia rozpočet RRZ a IFP?",List.of("rozpočet"),List.of("rrz-sk","ifp-sr")),
                new MockAi().generate(List.of(seed)),List.of(seed),store,guard,Instant.now().plusSeconds(120));
            result.documents().stream().skip(1).forEach(r -> System.out.println("research-live source="+r.sourceId()+" chars="+r.body().length()+" url="+r.originalUrl()));
            System.out.println("research-live gaps="+Json.write(result.gaps()));
            assertTrue(result.documents().stream().anyMatch(r -> r.sourceId().equals("rrz-sk")),result.gaps().toString());
            assertTrue(result.documents().stream().anyMatch(r -> r.sourceId().equals("ifp-sr")),result.gaps().toString());
            assertTrue(store.publicArticles.isEmpty());assertTrue(store.budgets.isEmpty());
        }
    }
}
