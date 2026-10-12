package sk.verejnydohlad;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.jsoup.Jsoup;
import sk.verejnydohlad.Models.*;

public final class Collector {
    private final SafeHttp http = new SafeHttp();
    public int collect(List<Source> sources, Store store, int max, Store.Guard guard, Instant deadline, List<String> errors) {
        int saved = 0, considered = 0; var robots = new Robots(http);
        for (Source source : sources) {
            if (!source.enabled() || considered >= max || Instant.now().isAfter(deadline)) continue;
            try {
                robots.requireAllowed(source.feedUrl(), source.allowedHosts());
                var items = new FeedParser().parse(http.get(source.feedUrl(), source.allowedHosts()), source);
                for (var item : items) {
                    if (considered >= max || Instant.now().isAfter(deadline)) break;
                    if (item.publishedAt().isBefore(Instant.now().minus(Duration.ofDays(7)))) continue;
                    if (store.knownRelease(Normalize.hash(item.url()))) continue;
                    considered++;
                    try {
                        String body = item.body();
                        if (!source.bodySelector().isBlank()) {
                            robots.requireAllowed(item.url(), source.allowedHosts()); Thread.sleep(1000);
                            var doc = Jsoup.parse(new String(http.get(item.url(), source.allowedHosts()), StandardCharsets.UTF_8), item.url());
                            var content = doc.select(source.bodySelector());
                            if (content.isEmpty()) throw new IllegalStateException("Configured body selector no longer matches");
                            body = Normalize.text(content.outerHtml());
                        }
                        if (body.length() < 120) throw new IllegalStateException("Feed does not provide adequate source text");
                        if (body.length() > 100_000) throw new IllegalStateException("Source body too large");
                        String hash = Normalize.hash(body), id = Normalize.hash(item.url());
                        String datePath = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC).format(item.publishedAt());
                        var release = new Release(id, source.sourceId(), source.name(), item.url(), item.publishedAt(), Instant.now(), item.title(), body, hash,
                            source.sourceId() + "/" + datePath + "/" + id + "-" + hash + ".json");
                        guard.check(); if (store.collect(release)) saved++;
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
                    catch (Exception e) { errors.add(source.sourceId() + ": entry failed (" + e.getClass().getSimpleName() + ")"); }
                }
                guard.check(); store.sourceChecked(source, Instant.now());
            } catch (Exception e) { errors.add(source.sourceId() + ": collection failed (" + e.getClass().getSimpleName() + ")"); }
        }
        return saved;
    }
}
