package dev.bernie.vct.parse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void parsesGrandFinalHeader() throws Exception {
        MatchPage p = parser.parse(734308, fixture("match-734308.html"));

        assertEquals(2977, p.eventId());
        assertEquals("VCT 2026: Americas Stage 2", p.eventName());
        assertEquals("Playoffs: Grand Final", p.stage());
        assertEquals(Instant.parse("2026-09-06T17:00:00Z"), p.startTime());
        assertEquals("13.04", p.patch());
        assertEquals(5, p.bestOf());
        assertEquals(new MatchPage.Team(120, "100 Thieves", "100T"), p.team1());
        assertEquals(new MatchPage.Team(6961, "LOUD", "LOUD"), p.team2());
        assertEquals(3, p.team1Score());
        assertEquals(2, p.team2Score());
        assertTrue(p.veto().startsWith("100T ban Abyss; 100T ban Lotus; 100T pick Split"));
        assertFalse(p.isShowmatch());
    }

    @Test
    void parsesEveryMapOfGrandFinal() throws Exception {
        List<MatchPage.MapResult> maps = parser.parse(734308, fixture("match-734308.html")).maps();

        assertEquals(List.of("Split", "Sunset", "Ascent", "Summit", "Haven"),
                maps.stream().map(MatchPage.MapResult::mapName).toList());
        assertEquals(List.of(1, 2, 1, 2, 0), maps.stream().map(MatchPage.MapResult::pickedBy).toList());
        assertEquals(List.of(7, 13, 14, 1, 14), maps.stream().map(MatchPage.MapResult::team1Score).toList());
        assertEquals(List.of(13, 11, 12, 13, 12), maps.stream().map(MatchPage.MapResult::team2Score).toList());

        // Haven: 100T started on defense, won 5 def + 7 atk + 2 in overtime; LOUD 7 atk + 5 def.
        MatchPage.MapResult haven = maps.get(4);
        assertEquals(280042, haven.gameId());
        assertEquals(5, haven.mapNumber());
        assertEquals("def", haven.team1StartSide());
        assertEquals(List.of(7, 5, 2, 7, 5, 0), List.of(haven.team1Atk(), haven.team1Def(), haven.team1Ot(),
                haven.team2Atk(), haven.team2Def(), haven.team2Ot()));
        assertTrue(maps.stream().allMatch(MatchPage.MapResult::hasSideSplit));
        assertEquals(3603, haven.durationSeconds()); // 1:00:03
    }

    @Test
    void parsesPlayerStatsBySide() throws Exception {
        MatchPage.MapResult haven = parser.parse(734308, fixture("match-734308.html")).maps().get(4);

        assertEquals(10, haven.players().size());
        assertEquals(5, haven.players().stream().filter(ps -> ps.team() == 1).count());

        MatchPage.PlayerStats timotino = haven.players().get(0);
        assertEquals(29893, timotino.playerId());
        assertEquals("Timotino", timotino.name());
        assertEquals("Canada", timotino.country());
        assertEquals(1, timotino.team());
        assertEquals("Neon", timotino.agent());

        MatchPage.SideStats both = timotino.sides().get(0);
        assertEquals("both", both.side());
        assertEquals(1.47, both.rating());
        assertEquals(289, both.acs());
        assertEquals(27, both.kills());
        assertEquals(85, both.kastPct());
        assertEquals(173, both.adr());
        assertEquals(24, both.hsPct());
        assertEquals(8, both.firstKills());
        assertEquals(2, both.firstDeaths());

        MatchPage.SideStats atk = timotino.sides().get(1);
        MatchPage.SideStats def = timotino.sides().get(2);
        assertEquals("atk", atk.side());
        assertEquals(1.30, atk.rating());
        assertEquals(13, atk.kills());
        assertEquals(1.65, def.rating());
        assertEquals(14, def.kills());
    }

    @Test
    void parsesRoundStrip() throws Exception {
        List<MatchPage.RoundResult> rounds = parser.parse(734308, fixture("match-734308.html")).maps().get(4).rounds();

        assertEquals(26, rounds.size());
        // Round 1: 100T defending, elimination. Round 3: LOUD attack, spike detonated.
        assertEquals(new MatchPage.RoundResult(1, 1, "def", "elim", 1, 0), rounds.get(0));
        assertEquals(new MatchPage.RoundResult(3, 2, "atk", "boom", 2, 1), rounds.get(2));
        assertEquals(new MatchPage.RoundResult(6, 1, "def", "time", 3, 3), rounds.get(5));
        assertEquals(new MatchPage.RoundResult(26, 1, "atk", "elim", 14, 12), rounds.get(25));
    }

    @Test
    void readsStatValues() {
        assertEquals(1.47, MatchPageParser.parseStat("1.47", true));
        assertEquals(85, MatchPageParser.parseStat("85%", false));
        assertEquals(12, MatchPageParser.parseStat("+12", false));
        assertNull(MatchPageParser.parseStat("", false));
        assertNull(MatchPageParser.parseStat("-", false));
    }

    @Test
    void stripsSponsorFromTeamName() {
        assertEquals("KOI", MatchPageParser.plainTeamName("Movistar KOI (KOI)"));
        assertEquals("Bilibili Gaming", MatchPageParser.plainTeamName("Guangzhou Huadu Bilibili Gaming (Bilibili Gaming)"));
        assertEquals("100 Thieves", MatchPageParser.plainTeamName("100 Thieves"));
    }

    @Test
    void parsesDurations() {
        assertEquals(2526, MatchPageParser.parseDuration("42:06"));
        assertEquals(3603, MatchPageParser.parseDuration(" 1:00:03 "));
        assertNull(MatchPageParser.parseDuration(""));
    }

    private String fixture() throws Exception {
        return fixture("match-744488.html");
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
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
