package sk.verejnydohlad;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public final class Robots {
    private final SafeHttp http;
    private final Map<String,String> cache = new HashMap<>();
    public Robots(SafeHttp http) { this.http = http; }
    public void requireAllowed(String url, List<String> hosts) {
        URI u = URI.create(url); String origin = u.getScheme() + "://" + u.getHost();
        String robots = cache.computeIfAbsent(origin, o -> {
            try { return new String(http.get(o + "/robots.txt", hosts), StandardCharsets.UTF_8); }
            catch (IllegalStateException e) { if ("Source HTTP 404".equals(e.getMessage())) return ""; throw e; }
        });
        if (!allowed(robots, u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()))) throw new IllegalStateException("Collection disallowed by robots.txt");
    }
    // Conservative: honor both generic rules and rules naming this collector. Longest path wins.
    public static boolean allowed(String robots, String path) {
        boolean relevant = false, inRules = false, allow = true; int longest = -1;
        for (String raw : robots.split("\\R")) {
            String line = raw.split("#",2)[0].trim(); int colon = line.indexOf(':'); if (colon < 0) continue;
            String key = line.substring(0,colon).trim().toLowerCase(Locale.ROOT), value = line.substring(colon+1).trim();
            if (key.equals("user-agent")) { if (inRules) { relevant = false; inRules = false; } relevant |= value.equals("*") || "verejnydohlad".contains(value.toLowerCase(Locale.ROOT)); }
            else if (key.equals("allow") || key.equals("disallow")) {
                inRules = true; if (!relevant || value.isEmpty()) continue;
                StringBuilder regex = new StringBuilder("^");
                for (int i=0;i<value.length();i++) { char c=value.charAt(i); regex.append(c=='*' ? ".*" : c=='$' && i==value.length()-1 ? "$" : Pattern.quote(""+c)); }
                if (Pattern.compile(regex.toString()).matcher(path).find() && (value.length() > longest || value.length() == longest && key.equals("allow"))) { longest=value.length(); allow=key.equals("allow"); }
            }
        }
        return allow;
    }
}
