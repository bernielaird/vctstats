package dev.bernie.vct.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a saved vlr.gg match overview page.
 *
 * For now this only finds the per-map game IDs, which fetch-logs needs to build
 * each map's 2D replay URL. Scores and player stats come later.
 */
public class MatchPageParser {

    // One tab per map, plus an "all" tab. Unplayed maps (e.g. map 3 of a 2-0) are disabled.
    static final String MAP_TAB_SELECTOR = ".vm-stats-gamesnav-item[data-game-id]";

    // One stats block per map. Used when there are no tabs, e.g. for a Bo1.
    static final String MAP_STATS_SELECTOR = ".vm-stats-game[data-game-id]";

    public List<Long> gameIds(String html) {
        Document doc = Jsoup.parse(html);
        Set<Long> ids = new LinkedHashSet<>();

        for (Element tab : doc.select(MAP_TAB_SELECTOR)) {
            if (!"1".equals(tab.attr("data-disabled"))) {
                addIfNumeric(ids, tab.attr("data-game-id"));
            }
        }
        if (ids.isEmpty()) {
            for (Element block : doc.select(MAP_STATS_SELECTOR)) {
                addIfNumeric(ids, block.attr("data-game-id"));
            }
        }
        return new ArrayList<>(ids);
    }

    /** Skips the "all" pseudo-game and anything else that isn't a real ID. */
    private static void addIfNumeric(Set<Long> ids, String value) {
        if (value.matches("\\d+")) {
            ids.add(Long.parseLong(value));
        }
    }

    // Despite the name, vlr.gg's data-utc-ts is US Eastern: "13:00:00" renders as "10:00 AM PDT".
    private static final ZoneId VLR_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter VLR_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** When the match was scheduled to start, or null if the page has no timestamp. */
    public Instant matchTime(String html) {
        Element el = Jsoup.parse(html).selectFirst(".match-header-date [data-utc-ts]");
        if (el == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(el.attr("data-utc-ts"), VLR_TS).atZone(VLR_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** True if a 2D replay page has at least one round of replay data. */
    public static boolean hasReplayData(String logsHtml) {
        return Jsoup.parse(logsHtml).selectFirst(".lg-map[data-map]") != null;
    }

    public static String logsUrl(long matchId, long gameId) {
        return ResultsPageParser.BASE_URL + "/" + matchId + "/?game=" + gameId + "&tab=logs";
    }
}
