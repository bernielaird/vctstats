package dev.bernie.vct.db;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kind of event a name describes. Derived from the name alone, using the
 * naming seen across 2023-2026 (see EventFilter for the full list).
 *
 * type:   league | kickoff | masters | champions | lcq | qualifier
 * region: Americas | EMEA | Pacific | China | International
 */
public record EventInfo(Integer year, String type, String region) {

    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern REGION = Pattern.compile("\\b(Americas|EMEA|Pacific|China)\\b");

    public static EventInfo of(String name) {
        Matcher y = YEAR.matcher(name);
        Integer year = y.find() ? Integer.parseInt(y.group(1)) : null;

        String type;
        if (name.contains("Last Chance Qualifier")) {
            type = "lcq";
        } else if (name.contains("Qualifier")) {
            type = "qualifier";             // Champions Tour 2023: Champions China Qualifier
        } else if (name.startsWith("Valorant Champions")) {
            type = "champions";
        } else if (name.contains("Masters") || name.contains("LOCK//IN")) {
            type = "masters";               // LOCK//IN 2023 was the Masters-level opener
        } else if (name.contains("Kickoff")) {
            type = "kickoff";
        } else {
            type = "league";                // "Americas League" (2023), "Stage 1/2" (2024+)
        }

        // Masters and Champions are international even when hosted in a region's city
        // ("Masters Shanghai"); the China Qualifier is regional.
        String region = "International";
        if (!type.equals("masters") && !type.equals("champions")) {
            Matcher r = REGION.matcher(name);
            if (r.find()) {
                region = r.group(1);
            }
        }
        return new EventInfo(year, type, region);
    }
}
