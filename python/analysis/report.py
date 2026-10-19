"""Player stats + WPA for a season or an event, with uncertainty and shrinkage.

Needs event_wpa (run python/wpa/event_wpa.py first). Examples, from the project root:
    .venv\\Scripts\\python python\\analysis\\report.py --season 2026
    .venv\\Scripts\\python python\\analysis\\report.py --event "VCT 2026: Americas Stage 2"
    .venv\\Scripts\\python python\\analysis\\report.py --season 2025 --min-rounds 1000 --top 40

Columns:
  rating, acs, kd, kast, adr   from vlr.gg's stat tables, averaged over rounds (all maps)
  net / gained / lost          WPA per round played, as % of a round win (replay maps only)
  low..high                    95% range for net
  shrunk                       net pulled toward the average, more for small samples
                               (empirical Bayes); the best single estimate, and the sort order
The full table is saved as CSV in data/exports/.
"""
import argparse
import os
import re
import sqlite3
import sys

import numpy as np
import pandas as pd

SHRINK_POOL_MIN_ROUNDS = 300  # players used to estimate how much players truly differ


def game_filter(season, event):
    where, params = ["NOT m.is_showmatch"], []
    if season:
        where.append("e.year = ?")
        params.append(season)
    if event:
        where.append("e.name = ?")
        params.append(event)
    sql = f"""SELECT mp.game_id FROM maps mp JOIN matches m USING (match_id) JOIN events e USING (event_id)
              WHERE {' AND '.join(where)}"""
    return sql, params


def build(conn, season, event):
    games, params = game_filter(season, event)
    q = lambda sql: pd.read_sql_query(sql.replace("{games}", games), conn, params=params * sql.count("{games}"))

    events = q("""SELECT e.name, COUNT(DISTINCT m.match_id) AS matches FROM events e JOIN matches m USING (event_id)
                  JOIN maps mp USING (match_id) WHERE mp.game_id IN ({games})
                  GROUP BY e.event_id ORDER BY MIN(m.start_time)""")
    stats = q("""
        WITH g AS (SELECT game_id, team1_score + team2_score AS rounds FROM maps WHERE game_id IN ({games}))
        SELECT s.player_id, p.name AS player, COUNT(*) AS maps, SUM(g.rounds) AS rounds,
               SUM(s.rating * g.rounds) / SUM(CASE WHEN s.rating IS NOT NULL THEN g.rounds END) AS rating,
               SUM(s.acs * g.rounds) * 1.0 / SUM(g.rounds) AS acs,
               SUM(s.kills) * 1.0 / NULLIF(SUM(s.deaths), 0) AS kd,
               SUM(s.kast_pct * g.rounds) * 1.0 / SUM(g.rounds) AS kast,
               SUM(s.adr * g.rounds) * 1.0 / SUM(g.rounds) AS adr,
               SUM(s.kills) AS kills, SUM(s.deaths) AS deaths, SUM(s.assists) AS assists,
               SUM(s.first_kills) AS fk, SUM(s.first_deaths) AS fd
        FROM player_map_stats s JOIN g USING (game_id) JOIN players p USING (player_id)
        WHERE s.side = 'both' GROUP BY s.player_id""")
    team = q("""
        SELECT s.player_id, t.tag AS team FROM player_map_stats s JOIN maps mp USING (game_id)
        JOIN matches m USING (match_id) JOIN teams t ON t.team_id = s.team_id
        WHERE s.side = 'both' AND mp.game_id IN ({games}) ORDER BY m.start_time""").groupby("player_id").last()

    # WPA per round played (0 for rounds where the player did nothing), replay maps only.
    played = q("""
        SELECT s.player_id, r.game_id, r.round_num FROM player_map_stats s
        JOIN maps mp ON mp.game_id = s.game_id AND mp.has_replay JOIN rounds r ON r.game_id = s.game_id
        WHERE s.side = 'both' AND s.game_id IN ({games})""")
    credit = q("""
        SELECT actor_id AS player_id, game_id, round_num, actor_credit AS wpa FROM event_wpa
        WHERE actor_id IS NOT NULL AND actor_credit IS NOT NULL AND game_id IN ({games})
        UNION ALL
        SELECT victim_id, game_id, round_num, victim_credit FROM event_wpa
        WHERE victim_id IS NOT NULL AND victim_credit IS NOT NULL AND game_id IN ({games})""")
    credit["pos"] = credit["wpa"].clip(lower=0)
    credit["neg"] = credit["wpa"].clip(upper=0)
    pr = credit.groupby(["player_id", "game_id", "round_num"])[["wpa", "pos", "neg"]].sum().reset_index()
    pr = played.merge(pr, how="left", on=["player_id", "game_id", "round_num"]).fillna(0)
    w = pr.groupby("player_id").agg(wpa_rounds=("wpa", "size"), net=("wpa", "mean"), sd=("wpa", "std"),
                                    gained=("pos", "mean"), lost=("neg", "mean")).reset_index()
    w["se"] = w["sd"] / np.sqrt(w["wpa_rounds"])

    # Empirical Bayes: true spread between players = observed spread minus sampling noise.
    pool = w[w["wpa_rounds"] >= SHRINK_POOL_MIN_ROUNDS]
    if len(pool) < 10:
        pool = w[w["wpa_rounds"] >= w["wpa_rounds"].median()]
    mean = np.average(pool["net"], weights=pool["wpa_rounds"])
    true_var = max(pool["net"].var() - (pool["se"] ** 2).mean(), 1e-8)
    w["shrunk"] = mean + true_var / (true_var + w["se"] ** 2) * (w["net"] - mean)

    d = stats.merge(team, on="player_id", how="left").merge(w, on="player_id", how="left")
    d["low"], d["high"] = d["net"] - 1.96 * d["se"], d["net"] + 1.96 * d["se"]
    for col in ("net", "shrunk", "low", "high", "gained", "lost"):
        d[col] = 100 * d[col]
    return d.sort_values("shrunk", ascending=False), events, 100 * mean, 100 * np.sqrt(true_var)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/vct.db")
    ap.add_argument("--season", type=int, help="e.g. 2026: every VCT event that year, Kickoff to Champions")
    ap.add_argument("--event", help='exact event name, e.g. "VCT 2026: Americas Stage 2"')
    ap.add_argument("--min-rounds", type=int, help="replay rounds needed to be listed (default 600 season, 300 event)")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--out-dir", default="data/exports")
    args = ap.parse_args()
    if not args.season and not args.event:
        ap.error("give --season and/or --event")
    sys.stdout.reconfigure(encoding="utf-8")
    min_rounds = args.min_rounds or (300 if args.event else 600)

    with sqlite3.connect(f"file:{args.db}?mode=ro", uri=True) as conn:
        if args.event and not conn.execute("SELECT 1 FROM events WHERE name = ?", (args.event,)).fetchone():
            close = [n for (n,) in conn.execute("SELECT name FROM events WHERE name LIKE ?", (f"%{args.event}%",))]
            sys.exit(f"No event named {args.event!r}." + (f" Did you mean: {'; '.join(close)}" if close else ""))
        d, events, mean, spread = build(conn, args.season, args.event)

    title = " / ".join(x for x in (str(args.season) if args.season else "", args.event or "") if x)
    print(f"{title}: {len(events)} events, {events['matches'].sum()} matches, {len(d)} players")
    if len(events) > 1:
        print("  " + "; ".join(f"{n} ({k})" for n, k in events.itertuples(index=False)))
    print(f"  Average net {mean:.2f}/rd; real spread between players about ±{spread:.2f}\n")

    show = d[d["wpa_rounds"] >= min_rounds].copy()
    show["range"] = show.apply(lambda r: f"{r['low']:.1f}..{r['high']:.1f}", axis=1)
    t = show[["player", "team", "maps", "wpa_rounds", "rating", "acs", "kd", "fk", "fd",
              "shrunk", "net", "range", "gained", "lost"]].copy()
    t["wpa_rounds"] = t["wpa_rounds"].astype(int)
    for col, nd in (("rating", 2), ("acs", 0), ("kd", 2), ("shrunk", 2), ("net", 2), ("gained", 1), ("lost", 1)):
        t[col] = t[col].round(nd)
    print(f"{len(show)} players with {min_rounds}+ replay rounds. Top {args.top} by shrunk net WPA per round:")
    print(t.head(args.top).to_string(index=False))
    print("\nBottom 5:")
    print(t.tail(5).to_string(index=False))
    if len(show) > 2:
        print(f"\nCorrelation of shrunk WPA with vlr.gg rating: {show['shrunk'].corr(show['rating']):.2f}")

    os.makedirs(args.out_dir, exist_ok=True)
    slug = re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")
    out = os.path.join(args.out_dir, f"players_{slug}.csv")
    d[["player", "team", "maps", "rounds", "wpa_rounds", "rating", "acs", "kd", "kast", "adr", "kills", "deaths",
       "assists", "fk", "fd", "shrunk", "net", "low", "high", "gained", "lost"]].round(3).to_csv(out, index=False)
    print(f"Saved all {len(d)} players to {out}")


if __name__ == "__main__":
    main()
