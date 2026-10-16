package dev.bernie.vct.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchPageParserTest {

    private final MatchPageParser parser = new MatchPageParser();

    @Test
    void findsEveryMapOfRealBo5() throws Exception {
        // FlyQuest RED vs MIBR GC, 2-3: all five maps were played.
        assertEquals(List.of(281335L, 281336L, 281337L, 281338L, 281339L), parser.gameIds(fixture()));
    }

    @Test
    void readsMatchTimeAsEasternTime() throws Exception {
        // data-utc-ts="2026-10-15 17:00:00" is US Eastern (EDT, UTC-4), not UTC.
        assertEquals(Instant.parse("2026-10-15T21:00:00Z"), parser.matchTime(fixture()));
    }

    @Test
    void detectsReplayData() {
        assertTrue(MatchPageParser.hasReplayData(
                "<div class=\"lg-map\" data-at=\"0\" data-map='{\"events\":[]}'></div>"));
        assertFalse(MatchPageParser.hasReplayData("<div class=\"vm-stats\">No logs</div>"));
    }

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/match-744488.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void skipsAllTabAndUnplayedMaps() {
        String html = """
            <a class="vm-stats-gamesnav-item" data-game-id="all" data-disabled="0">All</a>
            <a class="vm-stats-gamesnav-item" data-game-id="101" data-disabled="0">Map 1</a>
            <a class="vm-stats-gamesnav-item" data-game-id="102" data-disabled="0">Map 2</a>
            <a class="vm-stats-gamesnav-item" data-game-id="103" data-disabled="1">Map 3</a>""";

        assertEquals(List.of(101L, 102L), parser.gameIds(html));
    }

    @Test
    void fallsBackToStatsBlocksWithoutTabs() {
        String html = """
            <div class="vm-stats-game" data-game-id="all"></div>
            <div class="vm-stats-game" data-game-id="555"></div>""";

        assertEquals(List.of(555L), parser.gameIds(html));
    }

    @Test
    void buildsReplayUrl() {
        assertEquals("https://www.vlr.gg/744488/?game=281336&tab=logs",
                MatchPageParser.logsUrl(744488, 281336));
    }
}
