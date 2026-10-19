package dev.bernie.vct.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultsPageParserTest {

    private final ResultsPageParser parser = new ResultsPageParser();

    @Test
    void extractsMatchIdsAndDeduplicates() {
        String html = """
            <html><body>
              <a class="match-item" href="/412345/team-a-vs-team-b-vct-2026-americas">A vs B</a>
              <a class="match-item" href="/412346/team-c-vs-team-d-vct-2026-emea">C vs D</a>
              <a class="match-item" href="/412345/team-a-vs-team-b-vct-2026-americas">dup</a>
              <a class="nav-link" href="/news">not a match</a>
            </body></html>""";

        List<ResultsPageParser.MatchLink> links = parser.parse(html);

        assertEquals(2, links.size());
        assertEquals(412345L, links.get(0).matchId());
        assertEquals("https://www.vlr.gg/412345/team-a-vs-team-b-vct-2026-americas", links.get(0).url());
    }

    @Test
    void emptyPageMeansEndOfResults() {
        assertEquals(0, parser.parse("<html><body><p>No matches</p></body></html>").size());
    }

    @Test
    void parsesRealResultsPage() throws Exception {
        String html;
        try (var in = getClass().getResourceAsStream("/fixtures/results-page-1.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<ResultsPageParser.MatchLink> links = parser.parse(html);

        assertEquals(50, links.size());
        for (ResultsPageParser.MatchLink link : links) {
            assertTrue(link.matchId() > 0);
            assertTrue(link.url().startsWith("https://www.vlr.gg/" + link.matchId() + "/"));
        }

        // The stage ("Playoffs–Upper Quarterfinals") lives in a child div and must not leak in.
        assertTrue(links.stream().anyMatch(l -> l.eventName().equals("Valorant Champions 2026")));
        assertTrue(links.stream().anyMatch(l -> l.eventName().equals("Game Changers 2026: Pacific")));

        // That page has 30 Champions matches; the rest are Game Changers and a third-party event.
        assertEquals(30, links.stream().filter(l -> EventFilter.isWanted(l.eventName())).count());
    }

    @Test
    void parsesEveryAgeFormat() {
        assertEquals(Duration.ofHours(8).plusMinutes(19), ResultsPageParser.parseAge("8h 19m"));
        assertEquals(Duration.ofMinutes(45), ResultsPageParser.parseAge("45m"));
        assertEquals(Duration.ofDays(2).plusHours(3), ResultsPageParser.parseAge("2d 3h"));
        assertEquals(Duration.ofDays(23), ResultsPageParser.parseAge("3w 2d"));
        assertEquals(Duration.ofDays(150), ResultsPageParser.parseAge("5mo"));
        assertEquals(Duration.ofDays(730), ResultsPageParser.parseAge("2y"));
        assertNull(ResultsPageParser.parseAge("LIVE"));
    }

    @Test
    void readsAgeFromRealPage() throws Exception {
        String html;
        try (var in = getClass().getResourceAsStream("/fixtures/results-page-1.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The first match on the saved page had finished "8h 19m" earlier.
        assertEquals(Duration.ofHours(8).plusMinutes(19), parser.parse(html).get(0).age());
    }

    @Test
    void readsDateFromHeaderAboveEachMatch() throws Exception {
        String html;
        try (var in = getClass().getResourceAsStream("/fixtures/results-page-1.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<ResultsPageParser.MatchLink> links = parser.parse(html);

        // First header is "Thu, October 15, 2026 <span>Today</span>"; the second match is under "Yesterday".
        assertEquals(LocalDate.of(2026, 10, 15), links.get(0).date());
        assertEquals(LocalDate.of(2026, 10, 14), links.get(1).date());
        assertTrue(links.stream().allMatch(l -> l.date() != null));
    }

    @Test
    void readsEventNameWithoutStage() {
        String html = """
            <a class="match-item" href="/1/a-vs-b">
              <div class="match-item-event text-of">
                <div class="match-item-event-series text-of">Playoffs&ndash;Grand Final</div>
                Valorant Masters Toronto 2025
              </div>
            </a>""";

        assertEquals("Valorant Masters Toronto 2025", parser.parse(html).get(0).eventName());
    }
}
