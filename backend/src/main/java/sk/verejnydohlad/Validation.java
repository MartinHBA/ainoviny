package sk.verejnydohlad;

import java.util.*;
import java.util.regex.Pattern;
import sk.verejnydohlad.Models.*;

public final class Validation {
    public static final Set<String> CATEGORIES = Set.of("Slovensko", "Verejné peniaze", "Politika");
    public static void analysis(Analysis a) {
        if (a == null || a.score() < 0 || a.score() > 100 || a.topicKey() == null || !a.topicKey().matches("[a-z0-9-]{1,100}") || !CATEGORIES.contains(a.category()) || a.reason() == null || a.reason().length() > 1000)
            throw new IllegalArgumentException("Invalid newsworthiness analysis");
    }
    public static void draft(Draft d) {
        if (d == null || !length(d.title(),5,180) || !length(d.perex(),20,700) || !length(d.markdown(),80,14000)
            || !CATEGORIES.contains(d.category()) || d.claims() == null || d.claims().isEmpty() || d.claims().size()>30)
            throw new IllegalArgumentException("Invalid AI draft structure");
        for (Claim c : d.claims()) if (c == null || !length(c.text(),5,1500) || !length(c.sourceId(),1,64) || !length(c.evidence(),10,1800)) throw new IllegalArgumentException("Invalid claim map");
        if (Json.write(d.claims()).length() > 18000) throw new IllegalArgumentException("Claim map too large");
    }
    public static Verification grounded(Draft d, List<Release> sources, Verification ai) {
        draft(d);
        if (ai == null || ai.issues() == null || ai.issues().size() > 30 || ai.issues().stream().anyMatch(s -> s == null || s.length() > 1000) || Json.write(ai.issues()).length()>4000) throw new IllegalArgumentException("Invalid verification response");
        var issues = new ArrayList<>(ai.issues());
        var byId = new HashMap<String,Release>(); sources.forEach(s -> byId.put(s.id(), s));
        String article = d.title() + "\n\n" + d.perex() + "\n\n" + d.markdown();
        if (Pattern.compile("<[^>]+>|!\\[|https?://|javascript:|data:", Pattern.CASE_INSENSITIVE).matcher(article).find()) issues.add("HTML, images or embedded URLs require removal; sources are attached by the system.");
        Set<String> mapped = new HashSet<>();
        for (Claim c : d.claims()) {
            mapped.add(plain(c.text())); Release source = byId.get(c.sourceId());
            if (source == null || !Normalize.text(source.title()+" "+source.body()).contains(Normalize.text(c.evidence()))) { issues.add("Evidence is not present in the cited source: " + c.sourceId()); continue; }
            if (!plain(article).contains(plain(c.text()))) issues.add("Claim map contains text absent from the draft.");
            var numbers = Pattern.compile("\\d+(?:[.,]\\d+)*").matcher(c.text());
            Set<String> evidenceNumbers = new HashSet<>(); var evidence = Pattern.compile("\\d+(?:[.,]\\d+)*").matcher(c.evidence());
            while(evidence.find()) evidenceNumbers.add(evidence.group().replace(',','.'));
            while(numbers.find()) if(!evidenceNumbers.contains(numbers.group().replace(',','.'))) issues.add("Numeric claim is not grounded: " + numbers.group());
            var quotes = Pattern.compile("[„\"“]([^„\"“”]{8,})[\"“”]").matcher(c.text());
            while(quotes.find()) if(!Normalize.text(source.body()).contains(Normalize.text(quotes.group(1)))) issues.add("Quotation absent from source.");
        }
        // Every title, perex and body paragraph must have a claim/evidence mapping, including uncertainty statements.
        for (String paragraph : article.split("\\n\\s*\\n")) {
            String p = paragraph.strip(); if (p.isEmpty() || p.startsWith("#")) continue;
            if (!mapped.contains(plain(p))) issues.add("Unmapped article paragraph: " + p.substring(0, Math.min(70,p.length())));
        }
        boolean sensitive = ai.sensitive() || Pattern.compile("korup|trestn|podvod|sprenever|zneuž|obvin|pochyben|nezákonn", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(article).find();
        return new Verification(ai.passed() && issues.isEmpty(), sensitive, issues.stream().distinct().toList());
    }
    public static void editorDecision(EditorDecision d) {
        if(d==null || d.issues()==null || d.issues().size()>50 || d.issues().stream().anyMatch(s -> !length(s,1,3000)))
            throw new IllegalArgumentException("Invalid chief editor decision");
        if(d.approved() && (d.sensitive() || !d.issues().isEmpty())) throw new IllegalArgumentException("Contradictory chief editor approval");
        // An explicit clean approval can legitimately have no explanatory prose. Rejections need a reason.
        if(!length(d.reason(),d.approved()?0:1,3000)) throw new IllegalArgumentException("Invalid chief editor reason");
    }
    private static String plain(String value) { return Normalize.text(value.replaceAll("[*_`]+", "")); }
    private static boolean length(String value, int min, int max) { return value != null && value.strip().length() >= min && value.length() <= max; }
    public static void id(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid article id"); }
    public static void slug(String value) { if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,119}")) throw new IllegalArgumentException("Invalid article slug"); }
    public static void cursor(String value) { if (value != null && !value.matches("pub\\|[0-9]{19}\\|[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid pagination cursor"); }
    private Validation() {}
}
