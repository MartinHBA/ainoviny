package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.*;

public interface Ai {
    Map<String,Analysis> analyze(List<Release> releases);
    Draft generate(List<Release> releases);
    Verification verify(Draft draft, List<Release> releases);
    String model();
}
