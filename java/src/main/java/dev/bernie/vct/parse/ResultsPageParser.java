package dev.bernie.vct.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls match links (with their event names) out of a vlr.gg results-list page.
 */
public class ResultsPageParser {

    /**
     * {@code age} is how long ago the match finished and {@code date} the day it was played;
     * either is null if the page didn't say.
     */
    public record MatchLink(long matchId, String url, String eventName, Duration age, LocalDate date) {}

    // Checked against a saved results page (fixtures/results-page-1.html).
    static final String MATCH_LINK_SELECTOR = "a.match-item";

    // Holds the event name as its own text, plus a child div with the stage
    // ("Playoffs–Grand Final") that ownText() leaves out.
    static final String EVENT_SELECTOR = ".match-item-event";

    // "8h 19m", "2d 3h", "3w 2d", "5mo", "2y"
    static final String AGE_SELECTOR = ".ml-eta";

    // "Thu, October 15, 2026", placed before each day's group of matches.
    static final String DATE_HEADER_SELECTOR = ".wf-label.mod-large";
    private static final DateTimeFormatter HEADER_DATE =
            DateTimeFormatter.ofPattern("EEE, MMMM d, yyyy", Locale.US);

    // Match URLs look like /123456/team-a-vs-team-b-event-name
    private static final Pattern MATCH_PATH = Pattern.compile("^/(\\d+)/[^/]+");

    // "mo" must come before "m" so "5mo" isn't read as 5 minutes.
    private static final Pattern AGE_PART = Pattern.compile("(\\d+)\\s*(mo|y|w|d|h|m)");

    public static final String BASE_URL = "https://www.vlr.gg";

    public List<MatchLink> parse(String html) {
        Document doc = Jsoup.parse(html, BASE_URL);
        Set<Long> seen = new LinkedHashSet<>();
        List<MatchLink> links = new ArrayList<>();

        // Walk headers and matches in page order so each match picks up the date above it.
        LocalDate currentDate = null;
        for (Element a : doc.select(DATE_HEADER_SELECTOR + ", " + MATCH_LINK_SELECTOR)) {
            if (!a.is(MATCH_LINK_SELECTOR)) {
                currentDate = parseHeaderDate(a.ownText());
                continue;
            }
            String href = a.attr("href");
            Matcher m = MATCH_PATH.matcher(href);
            if (!m.find()) {
                continue;
            }
            long id = Long.parseLong(m.group(1));
            if (seen.add(id)) {
                Element event = a.selectFirst(EVENT_SELECTOR);
                String eventName = event == null ? "" : event.ownText().trim();
                Element age = a.selectFirst(AGE_SELECTOR);
                links.add(new MatchLink(id, a.absUrl("href"), eventName,
                        age == null ? null : parseAge(age.text()), currentDate));
            }
        }
        return links;
    }

    static LocalDate parseHeaderDate(String text) {
        try {
            return LocalDate.parse(text.trim(), HEADER_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Parses vlr.gg's rough "time ago" text. Months and years are approximate. */
    static Duration parseAge(String text) {
        Matcher m = AGE_PART.matcher(text);
        Duration total = Duration.ZERO;
        boolean found = false;
        while (m.find()) {
            found = true;
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "y" -> Duration.ofDays(365 * n);
                case "mo" -> Duration.ofDays(30 * n);
                case "w" -> Duration.ofDays(7 * n);
                case "d" -> Duration.ofDays(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofMinutes(n);
            });
        }
        return found ? total : null;
    }

    public static String resultsPageUrl(int page) {
        return BASE_URL + "/matches/results/?page=" + page;
    }
}
