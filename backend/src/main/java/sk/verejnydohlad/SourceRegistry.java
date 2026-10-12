package sk.verejnydohlad;

import java.util.*;
import sk.verejnydohlad.Models.Source;

public final class SourceRegistry {
    public static List<Source> load() {
        try (var stream = SourceRegistry.class.getResourceAsStream("/sources.json")) {
            if (stream == null) throw new IllegalStateException("Missing source registry");
            return Arrays.asList(Json.MAPPER.readValue(stream, Source[].class));
        } catch (Exception e) { throw new IllegalStateException("Invalid source registry", e); }
    }
    private SourceRegistry() {}
}
