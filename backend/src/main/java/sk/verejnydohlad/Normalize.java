package sk.verejnydohlad;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.jsoup.Jsoup;

public final class Normalize {
    public static String text(String html) {
        var doc = Jsoup.parse(html == null ? "" : html);
        doc.select("script,style,noscript").remove();
        return Normalizer.normalize(doc.text().replace('\u00a0', ' '), Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }
    public static String canonical(String value) {
        URI u = URI.create(value.strip()).normalize();
        if (!Set.of("https", "http").contains(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid public URL");
        String query = u.getRawQuery();
        if (query != null) query = Arrays.stream(query.split("&")).filter(p -> !p.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*")).sorted().reduce((a,b) -> a + "&" + b).orElse(null);
        String host = u.getHost().toLowerCase(Locale.ROOT);
        int port = u.getPort();
        if (port == 443 && u.getScheme().equals("https") || port == 80 && u.getScheme().equals("http")) port = -1;
        return u.getScheme() + "://" + host + (port < 0 ? "" : ":" + port) + (u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (query == null ? "" : "?" + query);
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String slug(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "clanok" : s.substring(0, Math.min(90, s.length())).replaceAll("-$", "");
    }
    public static Instant date(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing source publication date");
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_DATE_TIME, DateTimeFormatter.RFC_1123_DATE_TIME)) {
            try { return ZonedDateTime.parse(value.trim(), f).toInstant(); } catch (Exception ignored) {}
        }
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d.M.uuuu"))) {
            try { return LocalDate.parse(value.trim(), f).atStartOfDay(ZoneId.of("Europe/Bratislava")).toInstant(); } catch (Exception ignored) {}
        }
        throw new IllegalArgumentException("Unrecognized publication date");
    }
    private Normalize() {}
}
