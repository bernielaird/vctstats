package dev.bernie.vct.parse;

import java.util.List;

/**
 * One map's 2D replay page. Players are identified by replay-only IDs (e.g. 2813033),
 * which build-db maps to vlr.gg player IDs by name within the match.
 */
public record ReplayPage(List<Round> rounds) {

    /** {@code atkTeam} is 1 or 2, matching the match page's team order. */
    public record Round(int number, int atkTeam, List<Player> players, List<Event> events,
                        List<Loadout> loadouts) {}

    public record Player(long replayId, int team, String agent, String name) {}

    /**
     * A kill, plant, or defuse. For a kill, (x, y) is where the victim died and (fromX, fromY)
     * where the killer stood. Spike and fall deaths have no killer: {@code actorId} is null.
     * {@code team} is vlr.gg's raw field: the victim's team on kills, the actor's on plants
     * and defuses, so build-db takes teams from the roster instead. For plant/defuse, (x, y) is
     * the planter/defuser. {@code positions} is every living player at that moment, except a
     * kill's victim (whose spot is x, y).
     */
    public record Event(int index, Integer timeSeconds, String type, Long actorId, Long victimId,
                        Integer team, Double x, Double y, Double fromX, Double fromY,
                        String weapon, String site, boolean afterDetonation,
                        List<Position> positions) {}

    /** {@code facing} is the view angle in degrees, as the replay viewer draws it. */
    public record Position(long replayId, double x, double y, Integer facing) {}

    /** Buy-phase state: credits left after buying and spent during it, armor, and main gun. */
    public record Loadout(String name, String side, String agent, Integer creditsLeft,
                          Integer creditsSpent, String armor, String weapon) {}
}
