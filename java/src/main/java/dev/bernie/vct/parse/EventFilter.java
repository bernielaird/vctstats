package dev.bernie.vct.parse;

import java.util.regex.Pattern;

/**
 * Decides which events are worth scraping: official VCT leagues, Masters, and Champions.
 *
 * Matches the start of Riot's own naming, checked against every event in the 2023-2026 scrape:
 *   "VCT 2025: EMEA Stage 1"                      (2025 onward)
 *   "Champions Tour 2024: Masters Shanghai"       (2021-2024)
 *   "Valorant Champions 2026"
 *   "Valorant Masters Toronto 2025"
 * Anchoring at the start keeps out third-party events that merely contain the words,
 * like "Shanghai Esports Masters 2024" or "HERO ESPORTS ASIAN CHAMPIONS LEAGUE 2025".
 *
 * Then drops official-looking events that aren't tier-1 competition:
 * Game Changers, Challengers, Ascension, and the OFF//SEASON exhibitions.
 */
public final class EventFilter {

    private static final Pattern INCLUDE = Pattern.compile(
            "^(VCT \\d{4}:|Champions Tour \\d{4}:|Valorant Champions \\d{4}|Valorant Masters )");

    private static final Pattern EXCLUDE =
            Pattern.compile("Game Changers|Challengers|Ascension|OFF//SEASON", Pattern.CASE_INSENSITIVE);

    private EventFilter() {}

    public static boolean isWanted(String eventName) {
        String name = eventName.trim();
        return INCLUDE.matcher(name).find() && !EXCLUDE.matcher(name).find();
    }
}
