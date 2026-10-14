package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

public interface Ai {
    Map<String,Analysis> analyze(List<Release> releases);
    Draft generate(List<Release> releases);
    default Draft generate(List<Release> releases,ResearchBrief research) { return generate(releases); }
    default ResearchBrief research(List<Release> releases) { throw new UnsupportedOperationException("No research analyst configured"); }
    default Draft correct(Draft draft,List<Release> releases,List<String> issues) { throw new UnsupportedOperationException("No citation correction configured"); }
    Verification verify(Draft draft, List<Release> releases);
    default EditorDecision review(Draft draft, List<Release> releases) { throw new UnsupportedOperationException("No chief editor configured"); }
    default String editorModel() { throw new UnsupportedOperationException("No chief editor configured"); }
    String model();
}
