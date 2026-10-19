"""Test 1: is WPA a stable measure of a player, compared with rating, ACS, K/D and the rest?

A stat that measures skill should agree with itself: split each player's maps in two, compute the
stat on each half, and correlate the halves across players. Higher = more signal, less noise.

  odd/even   alternate maps in date order: both halves come from the same period (reliability)
  early/late first vs second half of the player's maps: does early form predict later form?

Only maps with a replay and a vlr.gg rating are used, so every stat is measured on the same rounds.
"Within role" removes each role's average first, so a stat can't look stable just by tracking role
(duelists always have high ACS). 95% ranges are from resampling players (bootstrap).

Run from the project root after event_wpa.py:
    .venv\\Scripts\\python python\\analysis\\stability.py
    .venv\\Scripts\\python python\\analysis\\stability.py --season 2026 --min-rounds 400
"""
import argparse
import sqlite3
import sys

import numpy as np
import pandas as pd

ROLES = {
    "duelist": {"Jett", "Raze", "Reyna", "Phoenix", "Yoru", "Neon", "Iso", "Waylay"},
    "initiator": {"Sova", "Breach", "Skye", "Kayo", "KAY/O", "Fade", "Gekko", "Tejo"},
    "controller": {"Brimstone", "Omen", "Viper", "Astra", "Harbor", "Clove"},
    "sentinel": {"Killjoy", "Cypher", "Sage", "Chamber", "Deadlock", "Vyse", "Veto"},
}
ROLE_OF = {agent.lower(): role for role, agents in ROLES.items() for agent in agents}

# name -> (numerator column, denominator column); each half's value is sum(num) / sum(den).
STATS = {
    "net WPA / round": ("wpa", "rounds"),
    "vlr.gg rating": ("rating_x_rounds", "rounds"),
    "ACS": ("acs_x_rounds", "rounds"),
    "K/D": ("kills", "deaths"),
    "KAST": ("kast_x_rounds", "rounds"),
    "ADR": ("adr_x_rounds", "rounds"),
    "kills / round": ("kills", "rounds"),
    "(FK - FD) / round": ("fk_minus_fd", "rounds"),
}


def load(db, season):
    where = "AND e.year = ?" if season else ""
    params = (season,) if season else ()
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        maps = pd.read_sql_query(f"""
            SELECT s.player_id, p.name AS player, s.game_id, m.start_time, s.agent,
                   mp.team1_score + mp.team2_score AS rounds,
                   s.rating, s.acs, s.kills, s.deaths, s.kast_pct, s.adr, s.first_kills, s.first_deaths
            FROM player_map_stats s
            JOIN maps mp USING (game_id) JOIN matches m USING (match_id) JOIN events e USING (event_id)
            JOIN players p USING (player_id)
            WHERE s.side = 'both' AND mp.has_replay AND NOT m.is_showmatch
              AND s.rating IS NOT NULL AND s.acs IS NOT NULL AND s.kills IS NOT NULL {where}""", conn, params=params)
        wpa = pd.read_sql_query("SELECT game_id, player_id, wpa_net FROM player_wpa_maps", conn)
    d = maps.merge(wpa, on=["game_id", "player_id"], how="left")
    d["wpa"] = d["wpa_net"].fillna(0.0)
    for col in ("rating", "acs", "kast_pct", "adr"):
        d[f"{col.replace('_pct', '')}_x_rounds"] = d[col] * d["rounds"]
    d["fk_minus_fd"] = d["first_kills"] - d["first_deaths"]
    d["role"] = d["agent"].str.split("/").str[0].str.lower().map(ROLE_OF)
    return d.sort_values(["player_id", "start_time", "game_id"])


def halves(d, split):
    """Per player, sums of every stat's parts in half 0 and half 1."""
    d = d.copy()
    order = d.groupby("player_id").cumcount()
    if split == "odd/even":
        d["half"] = order % 2
    else:
        n = d.groupby("player_id")["game_id"].transform("size")
        d["half"] = (order >= n / 2).astype(int)
    parts = sorted({c for pair in STATS.values() for c in pair})
    return d.groupby(["player_id", "half"])[parts].sum().unstack("half")


def values(h, min_rounds):
    """Each stat on each half, for players with enough rounds in both halves."""
    keep = (h[("rounds", 0)] >= min_rounds) & (h[("rounds", 1)] >= min_rounds)
    h = h[keep]
    out = {}
    for name, (num, den) in STATS.items():
        out[name] = pd.DataFrame({half: h[(num, half)] / h[(den, half)].replace(0, np.nan) for half in (0, 1)})
    return out, h.index


def corr(a, b):
    ok = a.notna() & b.notna()
    return np.corrcoef(a[ok], b[ok])[0, 1]


def bootstrap(vals, players, role=None, n=2000, seed=0):
    rng = np.random.default_rng(seed)
    idx = np.arange(len(players))
    names = list(vals)
    draws = {k: [] for k in names}
    for _ in range(n):
        s = rng.choice(idx, size=len(idx), replace=True)
        for k in names:
            v = vals[k].iloc[s]
            if role is not None:
                v = v.sub(v.groupby(role.iloc[s].to_numpy()).transform("mean"))
            draws[k].append(corr(v[0], v[1]))
    return {k: np.array(v) for k, v in draws.items()}


def table(vals, players, role=None):
    point = {}
    for k, v in vals.items():
        if role is not None:
            v = v.sub(v.groupby(role.to_numpy()).transform("mean"))
        point[k] = corr(v[0], v[1])
    boot = bootstrap(vals, players, role)
    rows = []
    for k in vals:
        lo, hi = np.percentile(boot[k], [2.5, 97.5])
        diff = boot["net WPA / round"] - boot[k]
        rows.append({"stat": k, "r": point[k], "95% range": f"{lo:.2f}..{hi:.2f}",
                     "full-sample reliability": 2 * point[k] / (1 + point[k]),
                     "WPA minus this (95%)": "" if k == "net WPA / round" else
                     f"{np.mean(diff):+.2f} ({np.percentile(diff, 2.5):+.2f}..{np.percentile(diff, 97.5):+.2f})"})
    return pd.DataFrame(rows).sort_values("r", ascending=False)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/vct.db")
    ap.add_argument("--season", type=int, help="one season only (default: all of 2023-2026)")
    ap.add_argument("--min-rounds", type=int, default=400, help="rounds needed in EACH half")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    d = load(args.db, args.season)
    main_role = d.groupby("player_id")["role"].agg(lambda r: r.mode().iat[0] if r.notna().any() else None)
    scope = f"season {args.season}" if args.season else "all seasons 2023-2026"
    print(f"Stability test, {scope}: {d['player_id'].nunique()} players, {len(d):,} player-maps "
          f"(replay maps with a vlr.gg rating). Players need {args.min_rounds}+ rounds in each half.")

    for split in ("odd/even", "early/late"):
        vals, players = values(halves(d, split), args.min_rounds)
        role = main_role.reindex(players).fillna("unknown")
        print(f"\n=== {split} split: {len(players)} players ===")
        t = table(vals, players)
        print(t.round(2).to_string(index=False))
        print(f"\n  within role (each role's average removed; roles: {role.value_counts().to_dict()}):")
        t = table(vals, players, role)
        print(t[["stat", "r", "95% range", "WPA minus this (95%)"]].round(2).to_string(index=False))

    # Does a stat predict the other stats' future? Early-half stat vs late-half WPA and rating.
    vals, players = values(halves(d, "early/late"), args.min_rounds)
    print("\n=== early-half stat predicting late-half WPA and late-half rating ===")
    rows = [{"early-half stat": k, "-> late WPA": corr(vals[k][0], vals["net WPA / round"][1]),
             "-> late rating": corr(vals[k][0], vals["vlr.gg rating"][1])} for k in vals]
    print(pd.DataFrame(rows).sort_values("-> late WPA", ascending=False).round(2).to_string(index=False))


if __name__ == "__main__":
    main()
