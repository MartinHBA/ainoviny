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
    @Test void publicationReadsFullUvJESSAndMartinOriginalsWithoutAIOrStorageWrites() {
        var urls=List.of(
            "https://www.vlada.gov.sk/tlacove-spravy/slovensko-sa-stane-100-vlastnikom-spolocnosti-jess-co-urychli-vystavbu-noveho-jadroveho-zdroja-v-jaslovskych-bohuniciach/",
            "https://www.vlada.gov.sk/tlacove-spravy/r-fico-nova-nemocnica-v-martine-ma-hotovu-hrubu-stavbu-a-vlada-garantuje-pokracovanie-vystavby-aj-v-roku-2027/");
        var originals=urls.stream().map(url->new Release(Normalize.hash(url),"uv-sr","Úrad vlády SR",url,Instant.EPOCH,Instant.EPOCH,"Live extraction check","","","read-only.json")).toList();
        var store=new MemoryStore();
        try(var guard=store.lock()) {
            var current=PublicationCheck.official().refresh(originals,guard,Instant.now().plusSeconds(90));
            assertTrue(current.get(0).body().length()>2500,"JESS original must include paragraphs after the lead");
            assertTrue(current.get(1).body().length()>1500,"Martin original must include financing/opening paragraphs");
            assertTrue(current.get(0).body().contains("JAVYS"));assertTrue(current.get(1).body().contains("2029"));
            current.forEach(r->System.out.println("uv-live chars="+r.body().length()+" url="+r.originalUrl()));
            assertTrue(store.releases.isEmpty());assertTrue(store.editorial().isEmpty());assertTrue(store.budgets.isEmpty());
        }
    }
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
