package sk.verejnydohlad;

import java.time.*;
import java.util.UUID;

/** Independent manual ledger; scheduled work retains the Bratislava-day ledger. */
record RunScope(String id) {
    RunScope {
        if(!"scheduled".equals(id) && (id==null || !UUID.fromString(id).toString().equals(id)))throw new IllegalArgumentException("Invalid manual run ID");
    }
    static RunScope scheduled(){return new RunScope("scheduled");}
    static RunScope adHoc(){return new RunScope(UUID.randomUUID().toString());}
    static RunScope manualInput(String input) {
        String value=input==null?"":input.strip();
        if(value.startsWith("\"") || value.startsWith("{"))try {
            var node=Json.tree(value);
            if(node.isTextual())value=node.asText();
            else if(node.isObject() && node.size()==1 && node.path("input").isTextual())value=node.path("input").asText();
        }catch(IllegalArgumentException ignored){/* Reject unsupported manual input below. */}
        // Queue binding preserves native admin input; timer binding replaces it with TimerInfo.
        if(value.equals("adhoc") || value.equals("manual-after-update"))return adHoc();
        if(value.startsWith("adhoc:"))return new RunScope(value.substring(6));
        throw new IllegalArgumentException("Expected adhoc or adhoc:UUID manual input");
    }
    boolean manual(){return !id.equals("scheduled");}
    String label(){return manual()?"adhoc|"+id:"scheduled";}
    String budgetKey(Clock clock){return manual()?label():date(clock);}
    static String date(Clock clock){return clock.instant().atZone(ZoneId.of("Europe/Bratislava")).toLocalDate().toString();}
    static boolean manualLabel(String value) {
        if(value==null || !value.startsWith("adhoc|"))return false;
        try {return new RunScope(value.substring(6)).manual();}catch(IllegalArgumentException e){return false;}
    }
}
