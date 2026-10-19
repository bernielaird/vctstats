package dev.bernie.vct.db;

import dev.bernie.vct.parse.MatchPage.RoundResult;
import dev.bernie.vct.parse.ReplayPage;
import dev.bernie.vct.parse.ReplayPage.Event;
import dev.bernie.vct.parse.ReplayPage.Loadout;
import dev.bernie.vct.parse.ReplayPage.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one map's replay into the rows of the game_states table: the WPA model's training data.
 *
 * Each round yields a state at round start, then one after every kill and plant, until the
 * round is decided. A state stops being added once a side is wiped out, the spike is defused,
 * or it detonates; actions after that can't change who wins. Everything is from the attackers'
 * point of view, and the label is whether they won the round.
 */
final class GameStates {

    /** One row of game_states. Loadouts and weapon counts cover living players only. */
    record State(int roundNum, int stateNum, Integer eventNum, int timeSeconds,
                 int atkTeam, int atkAlive, int defAlive, boolean planted, String plantSite,
                 Integer secondsSincePlant,
                 int atkLoadout, int defLoadout, int atkStartLoadout, int defStartLoadout,
                 int atkBank, int defBank,
                 int atkRifles, int defRifles, int atkSnipers, int defSnipers,
                 int atkHeavy, int defHeavy,
                 int atkScore, int defScore, boolean atkWon) {}

    /** What one player brought into the round. */
    private record Kit(int team, int value, int bank, boolean rifle, boolean sniper, boolean heavy) {}

    private GameStates() {}

    /**
     * @param rounds the map's round strip from the match page (winner, side, running score)
     * @return rows for every replay round that the strip also has; others are skipped
     */
    static List<State> build(ReplayPage replay, List<RoundResult> rounds) {
        Map<Integer, RoundResult> byNumber = new HashMap<>();
        for (RoundResult r : rounds) {
            byNumber.put(r.number(), r);
        }
        List<State> out = new ArrayList<>();
        for (ReplayPage.Round round : replay.rounds()) {
            RoundResult result = byNumber.get(round.number());
            if (result == null || round.atkTeam() != 1 && round.atkTeam() != 2 || result.winnerSide() == null) {
                continue;
            }
            RoundResult before = byNumber.get(round.number() - 1);
            out.addAll(round(round, result, before));
        }
        return out;
    }

    private static List<State> round(ReplayPage.Round round, RoundResult result, RoundResult before) {
        int atk = round.atkTeam();
        int winnerTeam = result.winner();
        boolean atkWon = winnerTeam == atk;

        // Running score before this round, turned around to attacker / defender.
        int t1Before = before == null ? 0 : before.team1Score();
        int t2Before = before == null ? 0 : before.team2Score();
        int atkScore = atk == 1 ? t1Before : t2Before;
        int defScore = atk == 1 ? t2Before : t1Before;

        Map<String, Long> idByName = new HashMap<>();
        Map<Long, Integer> teamOf = new HashMap<>();
        for (Player p : round.players()) {
            idByName.put(p.name(), p.replayId());
            teamOf.put(p.replayId(), p.team());
        }
        Map<Long, Kit> kits = new HashMap<>();
        for (Loadout l : round.loadouts()) {
            Long id = idByName.get(l.name());
            if (id == null) {
                continue;
            }
            int bank = (l.creditsSpent() == null ? 0 : l.creditsSpent()) + (l.creditsLeft() == null ? 0 : l.creditsLeft());
            kits.put(id, new Kit(teamOf.get(id), Prices.loadout(l.weapon(), l.armor()),
                    Math.min(bank, Prices.MAX_CREDITS),
                    // 77 buy rows have no gun listed; Set.of(...).contains(null) would throw.
                    l.weapon() != null && Prices.RIFLES.contains(l.weapon()),
                    l.weapon() != null && Prices.SNIPERS.contains(l.weapon()),
                    "heavy".equals(l.armor())));
        }

        Set<Long> alive = new HashSet<>(teamOf.keySet());
        int atkStart = sum(kits, alive, atk, Kit::value);
        int defStart = sum(kits, alive, 3 - atk, Kit::value);
        int atkBank = sum(kits, alive, atk, Kit::bank);
        int defBank = sum(kits, alive, 3 - atk, Kit::bank);

        List<State> out = new ArrayList<>();
        boolean planted = false;
        String site = null;
        Integer plantTime = null;
        out.add(state(round.number(), 0, null, 0, atk, alive, teamOf, kits, false, null, null,
                atkStart, defStart, atkBank, defBank, atkScore, defScore, atkWon));

        for (Event e : round.events()) {
            if (e.afterDetonation() || "defuse".equals(e.type()) || e.timeSeconds() == null) {
                break; // decided: nothing after this changes the winner
            }
            if ("kill".equals(e.type())) {
                alive.remove(e.victimId());
            } else if ("plant".equals(e.type())) {
                planted = true;
                site = e.site();
                plantTime = e.timeSeconds();
            } else {
                continue;
            }
            int a = count(alive, teamOf, atk);
            int d = count(alive, teamOf, 3 - atk);
            if (a == 0 || d == 0) {
                break; // a side is wiped out: the round is over
            }
            out.add(state(round.number(), out.size(), e.index(), e.timeSeconds(), atk, alive, teamOf, kits,
                    planted, site, planted ? e.timeSeconds() - plantTime : null,
                    atkStart, defStart, atkBank, defBank, atkScore, defScore, atkWon));
        }
        return out;
    }

    private static State state(int roundNum, int stateNum, Integer eventNum, int time, int atk,
                               Set<Long> alive, Map<Long, Integer> teamOf, Map<Long, Kit> kits,
                               boolean planted, String site, Integer sincePlant,
                               int atkStart, int defStart, int atkBank, int defBank,
                               int atkScore, int defScore, boolean atkWon) {
        int def = 3 - atk;
        return new State(roundNum, stateNum, eventNum, time, atk,
                count(alive, teamOf, atk), count(alive, teamOf, def), planted, site, sincePlant,
                sum(kits, alive, atk, Kit::value), sum(kits, alive, def, Kit::value), atkStart, defStart,
                atkBank, defBank,
                countKits(kits, alive, atk, Kit::rifle), countKits(kits, alive, def, Kit::rifle),
                countKits(kits, alive, atk, Kit::sniper), countKits(kits, alive, def, Kit::sniper),
                countKits(kits, alive, atk, Kit::heavy), countKits(kits, alive, def, Kit::heavy),
                atkScore, defScore, atkWon);
    }

    private static int count(Set<Long> alive, Map<Long, Integer> teamOf, int team) {
        int n = 0;
        for (long id : alive) {
            if (teamOf.get(id) == team) {
                n++;
            }
        }
        return n;
    }

    private static int sum(Map<Long, Kit> kits, Set<Long> alive, int team, java.util.function.ToIntFunction<Kit> f) {
        int total = 0;
        for (long id : alive) {
            Kit k = kits.get(id);
            if (k != null && k.team() == team) {
                total += f.applyAsInt(k);
            }
        }
        return total;
    }

    private static int countKits(Map<Long, Kit> kits, Set<Long> alive, int team, java.util.function.Predicate<Kit> f) {
        int n = 0;
        for (long id : alive) {
            Kit k = kits.get(id);
            if (k != null && k.team() == team && f.test(k)) {
                n++;
            }
        }
        return n;
    }
}
