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

    public record Team(long id, String name) {}

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
            Integer durationSeconds) {

        public boolean hasSideSplit() {
            return team1Atk != null;
        }
    }

    public boolean isShowmatch() {
        return stage != null && stage.toLowerCase().startsWith("showmatch");
    }
}
