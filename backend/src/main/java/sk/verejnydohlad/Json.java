package sk.verejnydohlad;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static String write(Object value) { try { return MAPPER.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("JSON serialization failed", e); } }
    public static <T> T read(String value, Class<T> type) { try { return MAPPER.readValue(value, type); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON for " + type.getSimpleName(), e); } }
    public static JsonNode tree(String value) { try { return MAPPER.readTree(value); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON", e); } }
    private Json() {}
}
