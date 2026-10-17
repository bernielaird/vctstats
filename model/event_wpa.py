"""Credit every change in round win probability to the action (and player) that caused it.

Needs state_win_prob and event_prob_before, written by model/train_wpa.py. Writes:

  event_wpa     one row per kill / plant / defuse: win probability before and after,
                the action's change, and the credit to the actor and the victim
  round_wpa     one row per round: start probability, total clock drift, how it ended,
                so every round adds up: start + clock + actions + ending = result
  player_wpa_maps (view)  gained / lost / net per player per map
  player_wpa      (view)  the same per player, all maps

How a change is split, for an action at time t after a state at time t0:
  clock  = P(previous state, moved on to t) - P(previous state at t0)   -> nobody
  action = P(after the action)              - P(previous state, at t)   -> actor / victim
The action that ends a round (the wiping kill or the defuse) moves P to 1 or 0.
Rounds that end without an action (spike detonation, time running out) put the rest in
round_wpa.ending_atk, credited to nobody.

Credit (to the player's own team, so positive = helped their team):
  kill       killer gets +change, victim gets -change
  teamkill   the teamkiller gets the (negative) change, the victim nothing
  spike/fall death (no killer)  the victim gets the change
  plant / defuse                the planter / defuser gets the change

Run from the project root after train_wpa.py:
    .venv\\Scripts\\python model\\event_wpa.py
"""
import argparse
import sqlite3
import time
from collections import defaultdict

import numpy as np
import pandas as pd

TRADE_WINDOW = 5  # seconds: killing your teammate's killer within this counts as a trade

COLUMNS = ["game_id", "round_num", "event_num", "time_seconds", "type", "action", "round_winning",
           "spike_planted", "atk_alive", "def_alive", "actor_id", "victim_id", "actor_team_id",
           "victim_team_id", "weapon", "attacker_id", "atk_start_loadout", "def_start_loadout",
           "prob_prev_state", "prob_before", "prob_after", "atk_change", "actor_credit", "victim_credit",
           "actor_credit_raw", "victim_credit_raw", "traded_by_event", "trade_refund"]
COL = {name: i for i, name in enumerate(COLUMNS)}

# Share of a trade kill's credit moved to the teammate whose death it avenged.
# 1.0 judges the traded death purely by the win probability after the trade (the trader keeps
# nothing); 0 is no adjustment. Tune with --compare.
DEFAULT_TRADE_REFUND = 0.5


def load(db: str):
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        states = pd.read_sql_query("""
            SELECT g.game_id, g.round_num, g.state_num, g.event_num, g.time_seconds, g.attacker_id,
                   g.defender_id, g.atk_alive, g.def_alive, g.spike_planted, g.atk_won,
                   g.atk_start_loadout, g.def_start_loadout, p.atk_win_prob
            FROM game_states g JOIN state_win_prob p USING (game_id, round_num, state_num)
            ORDER BY g.game_id, g.round_num, g.state_num""", conn)
        before = pd.read_sql_query("SELECT * FROM event_prob_before", conn)
        events = pd.read_sql_query("""
            SELECT game_id, round_num, event_num, time_seconds, type, actor_id, victim_id,
                   actor_team_id, victim_team_id, weapon, site, after_detonation
            FROM replay_events ORDER BY game_id, round_num, event_num""", conn)
        outcomes = pd.read_sql_query("SELECT game_id, round_num, outcome FROM rounds", conn)
    return states, before, events, outcomes


def label(ev, kills_so_far):
    """
    Action label for one event, given the earlier kills in the round. For a trade, also
    returns the kill being traded (the death of the actor's teammate), else None.
    """
    if ev.type != "kill":
        return ev.type, None
    if ev.actor_id is None or pd.isna(ev.actor_id):
        return ("spike_death" if ev.weapon == "Spike" else "environment_death"), None
    if ev.actor_team_id == ev.victim_team_id:
        return "teamkill", None
    if not kills_so_far:
        return "opening_kill", None
    for k in reversed(kills_so_far):
        if ev.time_seconds - k.time_seconds > TRADE_WINDOW:
            break
        # The victim had just killed one of the actor's teammates.
        if k.actor_id == ev.victim_id and k.victim_team_id == ev.actor_team_id:
            return "trade_kill", k
    return "kill", None


def credits(action: str, atk_change: float, attacker, actor_team, victim_team):
    """(actor credit, victim credit), each from that player's own team's point of view."""
    def for_team(team):
        return atk_change if team == attacker else -atk_change

    if action == "teamkill":
        return for_team(actor_team), 0.0
    if action in ("spike_death", "environment_death"):
        return None, for_team(victim_team)
    if action in ("plant", "defuse"):
        return for_team(actor_team), None
    return for_team(actor_team), for_team(victim_team)


def build(states, before, events, outcomes, trade_refund):
    pb = {(g, r, e): p for g, r, e, p in before.itertuples(index=False)}
    ev_by_round = defaultdict(list)
    for ev in events.itertuples(index=False):
        ev_by_round[(ev.game_id, ev.round_num)].append(ev)
    outcome = {(g, r): o for g, r, o in outcomes.itertuples(index=False)}

    rows, round_rows = [], []
    for (g, r), st in states.groupby(["game_id", "round_num"], sort=False):
        st = list(st.itertuples(index=False))
        s0 = st[0]
        attacker, result = s0.attacker_id, float(s0.atk_won)
        evs = {e.event_num: e for e in ev_by_round.get((g, r), [])}
        kills_so_far = []
        row_of = {}  # event_num -> index in rows, so a trade can adjust the death it avenged
        p_prev, prev = s0.atk_win_prob, s0
        clock_total = 0.0

        def add(ev, p_after, prev_state, is_ending):
            nonlocal clock_total
            p_before = pb.get((g, r, ev.event_num), prev_state.atk_win_prob)
            clock_total += p_before - prev_state.atk_win_prob
            change = p_after - p_before
            action, traded = label(ev, kills_so_far)
            actor_credit, victim_credit = credits(action, change, attacker, ev.actor_team_id, ev.victim_team_id)
            row = [g, r, ev.event_num, ev.time_seconds, ev.type, action,
                   int(is_ending and ev.type == "kill"), int(prev_state.spike_planted),
                   prev_state.atk_alive, prev_state.def_alive,
                   None if pd.isna(ev.actor_id) else int(ev.actor_id),
                   None if pd.isna(ev.victim_id) else int(ev.victim_id),
                   None if pd.isna(ev.actor_team_id) else int(ev.actor_team_id),
                   None if pd.isna(ev.victim_team_id) else int(ev.victim_team_id),
                   ev.weapon, attacker, prev_state.atk_start_loadout, prev_state.def_start_loadout,
                   prev_state.atk_win_prob, p_before, p_after, change, actor_credit, victim_credit,
                   actor_credit, victim_credit, None, None]

            # Trade: the avenged teammate's death is judged with the trade included. Part of the
            # trader's credit moves to them, so the team's total for the exchange is unchanged.
            if traded is not None and actor_credit > 0 and trade_refund > 0:
                refund = trade_refund * actor_credit
                row[COL["actor_credit"]] -= refund
                death = rows[row_of[traded.event_num]]
                if death[COL["victim_credit"]] is not None:
                    death[COL["victim_credit"]] += refund
                    death[COL["traded_by_event"]] = ev.event_num
                    death[COL["trade_refund"]] = refund

            row_of[ev.event_num] = len(rows)
            rows.append(row)
            if ev.type == "kill":
                kills_so_far.append(ev)

        for s in st[1:]:
            ev = evs.get(s.event_num)
            if ev is not None:
                add(ev, s.atk_win_prob, prev, False)
            prev = s

        # The action that ended the round, if any: first kill or defuse after the last state.
        last_event = -1 if pd.isna(prev.event_num) else prev.event_num
        ending = next((e for n, e in sorted(evs.items()) if n > last_event and e.type in ("kill", "defuse")
                       and not e.after_detonation and e.time_seconds is not None
                       and not pd.isna(e.time_seconds)), None)
        if ending is not None and (g, r, ending.event_num) in pb:
            add(ending, result, prev, True)
            residual, end_type = 0.0, ("defuse" if ending.type == "defuse" else "wipe")
        else:
            residual = result - prev.atk_win_prob
            end_type = {"boom": "detonation", "time": "time"}.get(outcome.get((g, r)), "unrecorded")
        round_rows.append((g, r, attacker, s0.atk_win_prob, clock_total, end_type, residual, result))

    return (pd.DataFrame(rows, columns=COLUMNS),
            pd.DataFrame(round_rows, columns=["game_id", "round_num", "attacker_id", "start_prob",
                                              "clock_atk", "end_type", "ending_atk", "atk_won"]))


SCHEMA = [
    """CREATE TABLE event_wpa (
        game_id INTEGER NOT NULL, round_num INTEGER NOT NULL, event_num INTEGER NOT NULL,
        time_seconds INTEGER,
        type TEXT NOT NULL,             -- kill | plant | defuse
        action TEXT NOT NULL,           -- opening_kill | trade_kill | kill | teamkill | spike_death |
                                        -- environment_death | plant | defuse
        round_winning INTEGER NOT NULL, -- the kill that wiped the last enemy
        spike_planted INTEGER NOT NULL, -- before this action
        atk_alive INTEGER NOT NULL, def_alive INTEGER NOT NULL,  -- before this action
        actor_id INTEGER, victim_id INTEGER, actor_team_id INTEGER, victim_team_id INTEGER,
        weapon TEXT, attacker_id INTEGER NOT NULL,
        atk_start_loadout INTEGER, def_start_loadout INTEGER,
        prob_prev_state REAL NOT NULL,  -- attackers' win prob at the previous state
        prob_before REAL NOT NULL,      -- ... moved on to this action's time
        prob_after REAL NOT NULL,       -- ... after it (1 or 0 if it ended the round)
        atk_change REAL NOT NULL,       -- prob_after - prob_before, attackers' view
        actor_credit REAL,              -- to the actor's team; NULL if no actor (trade-adjusted)
        victim_credit REAL,             -- to the victim's team; NULL if no victim (trade-adjusted)
        actor_credit_raw REAL,          -- before the trade adjustment
        victim_credit_raw REAL,
        traded_by_event INTEGER,        -- on a death that was traded: the trade kill's event_num
        trade_refund REAL,              -- credit moved from the trader to this traded death
        PRIMARY KEY (game_id, round_num, event_num)
    ) WITHOUT ROWID""",
    """CREATE TABLE round_wpa (
        game_id INTEGER NOT NULL, round_num INTEGER NOT NULL, attacker_id INTEGER NOT NULL,
        start_prob REAL NOT NULL,   -- attackers' win prob at round start
        clock_atk REAL NOT NULL,    -- total drift from time passing between actions
        end_type TEXT NOT NULL,     -- wipe | defuse | detonation | time | unrecorded
        ending_atk REAL NOT NULL,   -- change with no action behind it (detonation, time out)
        atk_won INTEGER NOT NULL,
        PRIMARY KEY (game_id, round_num)
    ) WITHOUT ROWID""",
    "CREATE INDEX idx_event_wpa_actor ON event_wpa(actor_id)",
    "CREATE INDEX idx_event_wpa_victim ON event_wpa(victim_id)",
    # Every credit a player received on a map, as the actor or as the victim.
    """CREATE VIEW player_wpa_maps AS
    WITH credit AS (
        SELECT game_id, actor_id AS player_id, actor_credit AS wpa FROM event_wpa WHERE actor_id IS NOT NULL
        UNION ALL
        SELECT game_id, victim_id, victim_credit FROM event_wpa WHERE victim_id IS NOT NULL AND victim_credit IS NOT NULL
    )
    SELECT c.game_id, c.player_id, s.team_id,
           SUM(CASE WHEN c.wpa > 0 THEN c.wpa ELSE 0 END) AS wpa_gained,
           SUM(CASE WHEN c.wpa < 0 THEN c.wpa ELSE 0 END) AS wpa_lost,
           SUM(c.wpa) AS wpa_net,
           mp.team1_score + mp.team2_score AS rounds
    FROM credit c
    JOIN maps mp ON mp.game_id = c.game_id
    JOIN player_map_stats s ON s.game_id = c.game_id AND s.player_id = c.player_id AND s.side = 'both'
    GROUP BY c.game_id, c.player_id""",
    """CREATE VIEW player_wpa AS
    SELECT w.player_id, p.name AS player, COUNT(*) AS maps, SUM(w.rounds) AS rounds,
           SUM(w.wpa_gained) AS wpa_gained, SUM(w.wpa_lost) AS wpa_lost, SUM(w.wpa_net) AS wpa_net,
           SUM(w.wpa_net) / SUM(w.rounds) AS net_per_round,
           SUM(w.wpa_gained) / SUM(w.rounds) AS gained_per_round,
           SUM(w.wpa_lost) / SUM(w.rounds) AS lost_per_round
    FROM player_wpa_maps w JOIN players p USING (player_id)
    GROUP BY w.player_id""",
]


def write(db, events, rounds):
    with sqlite3.connect(db) as conn:
        for name in ("player_wpa", "player_wpa_maps"):
            conn.execute(f"DROP VIEW IF EXISTS {name}")
        for name in ("event_wpa", "round_wpa"):
            conn.execute(f"DROP TABLE IF EXISTS {name}")
        for sql in SCHEMA:
            conn.execute(sql)
        conn.executemany(f"INSERT INTO event_wpa VALUES ({', '.join('?' * len(events.columns))})",
                         events.astype(object).where(events.notna(), None).itertuples(index=False, name=None))
        conn.executemany(f"INSERT INTO round_wpa VALUES ({', '.join('?' * len(rounds.columns))})",
                         rounds.itertuples(index=False, name=None))


def report(db, events, rounds):
    # Every round should add up: start + clock + actions + ending = result.
    actions = events.groupby(["game_id", "round_num"])["atk_change"].sum()
    r = rounds.set_index(["game_id", "round_num"])
    total = r["start_prob"] + r["clock_atk"] + actions.reindex(r.index).fillna(0) + r["ending_atk"]
    err = (total - r["atk_won"]).abs()
    print(f"{len(events):,} actions in {len(rounds):,} rounds. "
          f"Rounds that add up exactly: {100 * np.mean(err < 1e-9):.2f}% (worst error {err.max():.2e})")
    print("How rounds ended:", r["end_type"].value_counts().to_dict())
    print(f"Average size per round: actions {events.groupby(['game_id', 'round_num'])['atk_change'].apply(lambda x: x.abs().sum()).mean():.3f}, "
          f"clock {r['clock_atk'].abs().mean():.3f}, unassigned ending {r['ending_atk'].abs().mean():.3f}")

    e = events.copy()
    e["actor_side"] = np.where(e["actor_team_id"] == e["attacker_id"], "attacker", "defender")
    e.loc[e["actor_id"].isna(), "actor_side"] = "-"
    print("\nWhat each action is worth to the actor's team (average change in win probability):")
    t = e.groupby(["action", "actor_side"]).agg(n=("actor_credit", "size"), avg=("actor_credit", "mean"),
                                                victim_avg=("victim_credit", "mean"))
    print((t.assign(avg=lambda x: (100 * x["avg"]).round(1), victim_avg=lambda x: (100 * x["victim_avg"]).round(1))
           .rename(columns={"avg": "actor %", "victim_avg": "victim %"})).to_string())
    print("\nRound-winning kills:", f"{e['round_winning'].sum():,}, worth on average "
          f"{100 * e.loc[e['round_winning'] == 1, 'actor_credit'].mean():.1f}% to the killer's team")

    deaths = e[(e["type"] == "kill") & e["victim_credit"].notna() & (e["action"] != "teamkill")]
    traded = deaths["traded_by_event"].notna()
    print(f"\nDeaths: {traded.sum():,} traded within {TRADE_WINDOW}s, {(~traded).sum():,} not. "
          f"Average cost to the victim: traded {100 * deaths.loc[traded, 'victim_credit'].mean():.1f}% "
          f"(raw {100 * deaths.loc[traded, 'victim_credit_raw'].mean():.1f}%), "
          f"untraded {100 * deaths.loc[~traded, 'victim_credit'].mean():.1f}%")

    # The adjustment only moves credit between teammates: totals must not change.
    moved = (e[["actor_credit", "victim_credit"]].fillna(0).to_numpy().sum()
             - e[["actor_credit_raw", "victim_credit_raw"]].fillna(0).to_numpy().sum())
    print(f"Total credit changed by trade adjustment: {moved:.2e} (should be ~0)")

    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        top = pd.read_sql_query("""
            SELECT w.player, w.maps, w.rounds, ROUND(100 * w.gained_per_round, 1) AS gained,
                   ROUND(100 * w.lost_per_round, 1) AS lost, ROUND(100 * w.net_per_round, 2) AS net,
                   ROUND(AVG(s.rating), 2) AS vlr_rating
            FROM player_wpa w JOIN player_map_stats s ON s.player_id = w.player_id AND s.side = 'both'
            WHERE w.rounds >= 2000 GROUP BY w.player_id ORDER BY w.net_per_round DESC LIMIT 15""", conn)
        corr = pd.read_sql_query("""
            SELECT w.net_per_round, AVG(s.rating) AS rating FROM player_wpa w
            JOIN player_map_stats s ON s.player_id = w.player_id AND s.side = 'both' AND s.rating IS NOT NULL
            WHERE w.rounds >= 1000 GROUP BY w.player_id""", conn)
    print("\nTop net WPA per round (2,000+ rounds with replays); % of a round win per round played:")
    print(top.to_string(index=False))
    print(f"\nCorrelation of net WPA per round with vlr.gg rating (1,000+ rounds, {len(corr)} players): "
          f"{corr['net_per_round'].corr(corr['rating']):.2f}")


def compare(db, states, before, events, outcomes, refunds=(0.0, 0.25, 0.5, 0.75, 1.0)):
    """What different trade refunds do to kill values and to the most affected players."""
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        names = dict(conn.execute("SELECT player_id, name FROM players").fetchall())
        rating = dict(conn.execute("""SELECT player_id, AVG(rating) FROM player_map_stats
            WHERE side = 'both' AND rating IS NOT NULL GROUP BY player_id""").fetchall())
        rounds_played = dict(conn.execute("""SELECT s.player_id, SUM(mp.team1_score + mp.team2_score)
            FROM player_map_stats s JOIN maps mp USING (game_id)
            WHERE s.side = 'both' AND mp.has_replay GROUP BY s.player_id""").fetchall())

    summary, nets = [], {}
    for refund in refunds:
        e, _ = build(states, before, events, outcomes, refund)
        deaths = e[(e["type"] == "kill") & e["victim_credit"].notna() & (e["action"] != "teamkill")]
        traded = deaths["traded_by_event"].notna()
        credit = pd.concat([e[["actor_id", "actor_credit"]].set_axis(["player", "wpa"], axis=1),
                            e[["victim_id", "victim_credit"]].set_axis(["player", "wpa"], axis=1)]).dropna()
        net = credit.groupby("player")["wpa"].sum()
        per_round = pd.Series({p: v / rounds_played[p] for p, v in net.items() if rounds_played.get(p, 0) >= 2000})
        r = pd.Series({p: rating.get(p) for p in per_round.index}).dropna()
        nets[refund] = per_round
        summary.append({"refund": refund,
                        "trade kill %": 100 * e.loc[e["action"] == "trade_kill", "actor_credit"].mean(),
                        "opening kill %": 100 * e.loc[e["action"] == "opening_kill", "actor_credit"].mean(),
                        "other kill %": 100 * e.loc[e["action"] == "kill", "actor_credit"].mean(),
                        "traded death %": 100 * deaths.loc[traded, "victim_credit"].mean(),
                        "untraded death %": 100 * deaths.loc[~traded, "victim_credit"].mean(),
                        "corr w/ rating": per_round[r.index].corr(r)})
    print("Effect of the trade refund (average credit to the actor / victim's team):")
    print(pd.DataFrame(summary).round(2).to_string(index=False))

    shift = (nets[refunds[-1]] - nets[refunds[0]]).sort_values()
    print(f"\nPlayers moved most between refund {refunds[0]} and {refunds[-1]} (net WPA % per round):")
    for label_, ids in (("helped (traded often)", shift.index[::-1][:8]), ("hurt (trade a lot)", shift.index[:8])):
        print(f"  {label_}:")
        for p in ids:
            vals = "  ".join(f"{100 * nets[x][p]:5.2f}" for x in refunds)
            print(f"    {names.get(p, p):14} {vals}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/vct.db")
    ap.add_argument("--trade-refund", type=float, default=DEFAULT_TRADE_REFUND,
                    help="share of a trade kill's credit given back to the traded teammate (0-1)")
    ap.add_argument("--compare", action="store_true",
                    help="print results for several --trade-refund values; writes nothing")
    args = ap.parse_args()
    started = time.time()
    states, before, events, outcomes = load(args.db)
    if args.compare:
        compare(args.db, states, before, events, outcomes)
        return
    ev, rounds = build(states, before, events, outcomes, args.trade_refund)
    write(args.db, ev, rounds)
    print(f"Trade refund: {args.trade_refund:.2f} of each trade kill's credit goes to the traded teammate")
    report(args.db, ev, rounds)
    print(f"\nWrote event_wpa, round_wpa, player_wpa_maps, player_wpa to {args.db} in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
