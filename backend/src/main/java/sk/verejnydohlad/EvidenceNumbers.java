package sk.verejnydohlad;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Exact decimal values only: no rounding, unit conversion or interpretation of dates. */
final class EvidenceNumbers {
    private static final Pattern TOKEN=Pattern.compile("\\d+(?:[.,]\\d+)*");
    static String canonical(String token) {
        String normalized=token.replace(',','.');
        try {return new BigDecimal(normalized).stripTrailingZeros().toPlainString();}
        catch(NumberFormatException notDecimal) {return normalized;}
    }
    static List<String> missing(String text,String evidence) {
        var found=new HashSet<String>();var matcher=TOKEN.matcher(evidence);
        while(matcher.find())found.add(canonical(matcher.group()));
        var missing=new LinkedHashSet<String>();matcher=TOKEN.matcher(text);
        while(matcher.find())if(!found.contains(canonical(matcher.group())))missing.add(matcher.group());
        return List.copyOf(missing);
    }
    private EvidenceNumbers() {}
}
