package dev.bernie.vct.parse;

import java.time.Instant;
import java.util.List;

/** Everything build-db reads from one saved match overview page. Team 1 is the left-hand team. */
public record MatchPage(
        long matchId,
        long eventId,
        String eventName,
        String stage,          // "Playoffs: Grand Final"
        Instant startTime,     // null if the page has no timestamp
        String patch,          // "13.04", or null (missing on ~1 in 5 pages)
        int bestOf,            // 0 if the page doesn't say
        Team team1,
        Team team2,
        int team1Score,
        int team2Score,
        String veto,           // "100T ban Abyss; ...; Haven remains", or null
        List<MapResult> maps) {

    /** {@code tag} ("100T") comes from the round strip and is null if no map has one. */
    public record Team(long id, String name, String tag) {}

    /**
     * One played map. Rounds are split by side: atk/def in regulation plus overtime.
     * {@code pickedBy} is 1 or 2, or 0 for a decider / unknown.
     * The side split and start side are null when vlr.gg doesn't show them (a few pages
     * list "0 / 0" for a 13-6 map); storing zeros there would be wrong, not just missing.
     */
    public record MapResult(
            long gameId,
            int mapNumber,
            String mapName,
            int pickedBy,
            int team1Score,
            int team2Score,
            String team1StartSide, // "atk" or "def"
            Integer team1Atk, Integer team1Def, Integer team1Ot,
            Integer team2Atk, Integer team2Def, Integer team2Ot,
            Integer durationSeconds,
            List<PlayerStats> players,
            List<RoundResult> rounds) {

        public boolean hasSideSplit() {
            return team1Atk != null;
        }
    }

    /** One player's line on one map: {@code team} is 1 or 2, {@code sides} has both/atk/def. */
    public record PlayerStats(long playerId, String name, String country, int team, String agent,
                              List<SideStats> sides) {}

    /** Any value can be null: vlr.gg leaves cells blank on some older or odd pages. */
    public record SideStats(String side, Double rating, Integer acs, Integer kills, Integer deaths,
                            Integer assists, Integer kastPct, Integer adr, Integer hsPct,
                            Integer firstKills, Integer firstDeaths) {}

    /**
     * One round from the overview's round strip. {@code winner} is 1 or 2; {@code winnerSide}
     * is atk/def; {@code outcome} is elim, boom (spike detonated), defuse, or time.
     * Scores are the running score after this round.
     */
    public record RoundResult(int number, int winner, String winnerSide, String outcome,
                              int team1Score, int team2Score) {}

    public boolean isShowmatch() {
        return stage != null && stage.toLowerCase().startsWith("showmatch");
    }
}
