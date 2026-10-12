package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;

final class Fixtures {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"),ZoneOffset.UTC);
    static Config config() {return Config.from(Map.of());}
    static Release release(String seed) {
        String body="Syntetická testovacia organizácia uvádza skúšobnú informáciu pre lokálny vývoj. Ide o označený testovací podklad, ktorý nie je skutočnou správou. "+seed;
        String url="https://example.invalid/synthetic-"+Normalize.slug(seed),id=Normalize.hash(url);
        return new Release(id,"synthetic","Syntetická testovacia organizácia",url,CLOCK.instant(),CLOCK.instant(),"[SYNTHETIC] Testovacia správa",body,Normalize.hash(body),"synthetic/2026/10/"+id+".json");
    }
    static Article generated(MemoryStore store) {
        store.collect(release("fixture"));new Pipeline(store,new MockAi(),config(),CLOCK).run(false);return store.editorial().getFirst();
    }
    static Source source() {return new Source("synthetic","Synthetic fixture","https://example.invalid/","https://example.invalid/feed","RSS",true,"Synthetic test only","",List.of("example.invalid"));}
}
