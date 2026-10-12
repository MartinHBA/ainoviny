package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

public interface Ai {
    Map<String,Analysis> analyze(List<Release> releases);
    Draft generate(List<Release> releases);
    Verification verify(Draft draft, List<Release> releases);
    default EditorDecision review(Draft draft, List<Release> releases) { throw new UnsupportedOperationException("No chief editor configured"); }
    default String editorModel() { throw new UnsupportedOperationException("No chief editor configured"); }
    String model();
}
