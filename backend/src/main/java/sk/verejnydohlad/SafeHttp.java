package sk.verejnydohlad;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Only registry hosts, no redirects, no private-network requests, bounded body/time/retries. */
public final class SafeHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public byte[] get(String url, List<String> allowedHosts) {
        URI uri = URI.create(url);
        validate(uri, allowedHosts);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent", "VerejnyDohlad/1.0 (public editorial feed collector)").GET().build();
                var response = client.send(request, ignored -> new BoundedBody(2_000_000));
                {
                    if (response.statusCode() == 200) {
                        return response.body();
                    }
                    if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < 2) {
                        long wait = Math.min(10, response.headers().firstValue("Retry-After").map(v -> { try { return Long.parseLong(v); } catch (Exception e) { return 2L; } }).orElse(1L << attempt));
                        Thread.sleep(Math.max(1, wait) * 1000); continue;
                    }
                    throw new IllegalStateException("Source HTTP " + response.statusCode());
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Collection interrupted"); }
            catch (java.io.IOException e) { if (attempt == 2) throw new IllegalStateException("Source unavailable", e); }
        }
        throw new IllegalStateException("Source unavailable");
    }
    public static void validate(URI uri, List<String> hosts) {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null || !hosts.contains(uri.getHost()) || uri.getPort() != -1 && uri.getPort() != 443)
            throw new IllegalArgumentException("Source URL outside approved hosts");
        try {
            for (var ip : InetAddress.getAllByName(uri.getHost()))
                if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isMulticastAddress()) throw new IllegalArgumentException("Source resolves to private address");
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Source host unavailable"); }
    }
}
