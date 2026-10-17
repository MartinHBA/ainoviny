package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

/** Deliberately scores real sources zero. Offline synthetic fixtures require an explicit marker. */
public final class MockAi implements Ai {
    public Map<String,Analysis> analyze(List<Release> sources) {
        var result = new HashMap<String,Analysis>();
        for (Release s : sources) result.put(s.id(), new Analysis(s.title().startsWith("[SYNTHETIC]") ? 80 : 0, "offline-"+s.id().substring(0,12), "Slovensko", "Offline model; only marked synthetic fixtures are eligible."));
        return result;
    }
    public Draft generate(List<Release> sources) {
        Release s = sources.getFirst();
        if (!s.title().startsWith("[SYNTHETIC]")) throw new IllegalArgumentException("Mock AI only accepts synthetic fixtures");
        String title = "[SYNTHETIC] Testovacia správa pre lokálny vývoj";
        String perex = "Podľa syntetického testovacieho podkladu ide o skúšobnú informáciu určenú výlučne na overenie systému.";
        String body = "Syntetická testovacia organizácia uvádza skúšobnú informáciu. Tento článok je označená testovacia pomôcka pre lokálny vývoj.";
        String evidence = s.body();
        return new Draft(title, perex, "Slovensko", body, List.of(new Claim(title,s.id(),evidence),new Claim(perex,s.id(),evidence),new Claim(body,s.id(),evidence)));
    }
    public Verification verify(Draft d, List<Release> sources) { return new Verification(true,false,List.of()); }
    public EditorDecision review(Draft d, List<Release> sources) {
        if (sources.stream().anyMatch(s -> !s.title().startsWith("[SYNTHETIC]"))) throw new IllegalArgumentException("Mock editor accepts synthetic fixtures only");
        return new EditorDecision(true,false,List.of(),"Marked synthetic fixture approved for local testing only.");
    }
    public String editorModel() { return "mock-offline-editor"; }
    public String model() { return "mock-offline-synthetic"; }
    public EditionPlan edition(List<EditionItem> pending,List<EditionItem> published){return new EditionPlan(List.of());}
    public List<StoryPitch> commission(List<Release> sources,List<Article> history) {
        return sources.stream().filter(s->s.title().startsWith("[SYNTHETIC]")).limit(6)
            .map(s->new StoryPitch(s.id(),List.of(s.id()),"Čo uvádza syntetický testovací podklad?","EXPLAINER",80,"Synthetic fixture only")).toList();
    }
    public ResearchPlan plan(Draft draft,List<Release> sources){return new ResearchPlan("Čo uvádza testovací podklad?",List.of("syntetický podklad"),List.of("rrz-sk"));}
    public ResearchBrief research(Draft draft,List<Release> sources,List<String> gaps){var r=sources.getFirst();return new ResearchBrief(false,false,"Čo uvádza testovací podklad?",List.of(new GenerationClaim(r.body(),r.id(),"body-0")),gaps,List.of());}
    public RevisionFeedback feedback(Draft draft,List<Release> sources,List<String> issues){return new RevisionFeedback("DROP",List.of(),"Synthetic mock does not repair rejected production material.");}
}
