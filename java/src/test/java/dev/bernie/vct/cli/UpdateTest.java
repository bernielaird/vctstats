package dev.bernie.vct.cli;

import dev.bernie.vct.store.MatchQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateTest {

    private MatchQueue queue;
    private final List<Integer> requested = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        queue = new MatchQueue(":memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        queue.close();
    }

    /** One results-page entry, shaped like vlr.gg's markup. */
    private static String match(long id, String event, String age) {
        return """
            <a class="match-item" href="/%d/x-vs-y">
              <div class="ml-eta mod-completed">%s</div>
              <div class="match-item-event">%s</div>
            </a>""".formatted(id, age, event);
    }

    private Main.PageSource pages(Map<Integer, String> byNumber) {
        return n -> {
            requested.add(n);
            return byNumber.getOrDefault(n, "<html></html>");
        };
    }

    @Test
    void stopsOnePageAfterReachingKnownMatches() throws Exception {
        queue.enqueue(300, "https://www.vlr.gg/300/x-vs-y"); // from the earlier full scrape

        Map<Integer, String> site = Map.of(
                1, match(500, "VCT 2026: Americas Stage 2", "1d 2h"),
                2, match(400, "VCT 2026: EMEA Stage 2", "3d 1h") + match(300, "VCT 2026: EMEA Stage 2", "3d 4h"),
                3, match(250, "VCT 2026: Pacific Stage 2", "4d 1h"),
                4, match(200, "VCT 2026: Pacific Stage 2", "5d 1h"));

        int added = Main.discoverNew(pages(site), queue, 30, Main.DEFAULT_SINCE);

        assertEquals(List.of(1, 2, 3), requested); // page 3 is the buffer; page 4 never fetched
        assertEquals(3, added);                    // 500, 400, and late-posted 250
        assertTrue(queue.contains(250));
        assertFalse(queue.contains(200));
    }

    @Test
    void leavesTooRecentMatchesForNextRun() throws Exception {
        queue.enqueue(300, "https://www.vlr.gg/300/x-vs-y");

        Map<Integer, String> site = Map.of(
                1, match(500, "VCT 2026: Americas Stage 2", "2h 10m")
                        + match(400, "VCT 2026: Americas Stage 2", "1d 1h")
                        + match(300, "VCT 2026: Americas Stage 2", "2d 1h"));

        Main.discoverNew(pages(site), queue, 30, Main.DEFAULT_SINCE);

        assertFalse(queue.contains(500)); // finished 2h ago: stats may not be posted
        assertTrue(queue.contains(400));
    }

    private static String day(String header) {
        return "<div class=\"wf-label mod-large\">" + header + "</div>";
    }

    @Test
    void stopsAtDateCutoff() throws Exception {
        // Fresh database, as in a first discover: nothing known, so only the cutoff stops it.
        Map<Integer, String> site = Map.of(
                1, day("Sat, January 14, 2023") + match(30, "VCT 2023: LOCK//IN Sao Paulo", "3y"),
                2, day("Sun, January 1, 2023") + match(20, "Champions Tour 2023: Something", "3y")
                        + day("Fri, December 30, 2022") + match(19, "Valorant Champions 2022", "3y"),
                3, day("Sun, December 11, 2022") + match(10, "Valorant Champions 2022", "3y"),
                4, day("Sat, December 10, 2022") + match(9, "Valorant Champions 2022", "3y"));

        int added = Main.discoverNew(pages(site), queue, 30, LocalDate.of(2023, 1, 1));

        assertEquals(List.of(1, 2, 3), requested); // page 3 is all 2022, so it stops there
        assertEquals(2, added);
        assertTrue(queue.contains(20));            // the cutoff day itself is included
        assertFalse(queue.contains(19));
    }

    @Test
    void skipsUnwantedEventsAndKeepsGoingThroughOffseason() throws Exception {
        queue.enqueue(100, "https://www.vlr.gg/100/x-vs-y");

        // Pages 1-2 are all Game Changers / tier 2, so no known match is seen until page 3.
        Map<Integer, String> site = Map.of(
                1, match(900, "Game Changers 2026: Pacific", "1d 1h"),
                2, match(800, "Challengers 2026: North America", "2d 1h"),
                3, match(100, "Valorant Champions 2026", "3d 1h"),
                4, match(50, "Valorant Champions 2026", "4d 1h"),
                5, match(10, "Valorant Champions 2026", "5d 1h"));

        int added = Main.discoverNew(pages(site), queue, 30, Main.DEFAULT_SINCE);

        assertEquals(List.of(1, 2, 3, 4), requested);
        assertEquals(1, added); // only 50, from the buffer page
        assertFalse(queue.contains(900));
    }
}
