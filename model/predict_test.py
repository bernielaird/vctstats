"""Test 2: do players' past stats predict who wins their FUTURE maps, and does WPA add anything?

For every map, in date order, each team gets the average of its five players' records built only
from EARLIER matches. A one-feature logistic regression on (team 1 - team 2) predicts the map
winner. Every stat is scored on exactly the same maps.

  - Shrinkage: each player's record is pulled toward the average by k "phantom" rounds of average
    play. Noisy stats need a bigger k, so k is tuned separately per stat on the training period.
  - Train (tune k, fit the regression): maps before --test-from. Test: maps from --test-from on.
  - Baselines: coin flip, and team Elo (team results only: no players, blind to roster changes).
  - Combinations: Elo + a player stat, and rating + WPA, to see what each adds.
  - 95% ranges on test log-loss differences come from resampling matches (bootstrap).

Run from the project root after event_wpa.py:
    .venv\\Scripts\\python model\\predict_test.py
"""
import argparse
import sqlite3
import sys
from collections import defaultdict

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import log_loss

K_GRID = [25, 50, 100, 200, 400, 800, 1600, 3200, 6400]
ELO_K_GRID = [8, 16, 24, 32, 48, 64]
MIN_PRIOR_ROUNDS = 100  # every one of the ten players needs this much history in every stat

# stat -> (numerator column, denominator column) per player-map
STATS = {
    "net WPA / round": ("wpa", "wpa_rounds"),
    "vlr.gg rating": ("rating_x_rounds", "rated_rounds"),
    "ACS": ("acs_x_rounds", "rounds"),
    "K/D": ("kills", "deaths"),
    "KAST": ("kast_x_rounds", "rounds"),
    "ADR": ("adr_x_rounds", "rounds"),
    "kills / round": ("kills", "rounds"),
    "(FK - FD) / round": ("fk_minus_fd", "rounds"),
}
WPA = "net WPA / round"


def load(db):
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        pm = pd.read_sql_query("""
            SELECT s.game_id, mp.match_id, m.start_time, s.player_id, s.team_id,
                   mp.team1_score + mp.team2_score AS rounds, mp.has_replay,
                   s.rating, s.acs, s.kills, s.deaths, s.kast_pct, s.adr, s.first_kills, s.first_deaths
            FROM player_map_stats s JOIN maps mp USING (game_id) JOIN matches m USING (match_id)
            WHERE s.side = 'both' AND NOT m.is_showmatch""", conn)
        maps = pd.read_sql_query("""
            SELECT mp.game_id, mp.match_id, m.start_time, m.team1_id, m.team2_id, mp.winner_id
            FROM maps mp JOIN matches m USING (match_id)
            WHERE NOT m.is_showmatch AND mp.winner_id IS NOT NULL ORDER BY m.start_time, mp.map_number""", conn)
        wpa = pd.read_sql_query("SELECT game_id, player_id, wpa_net FROM player_wpa_maps", conn)
    pm = pm.merge(wpa, on=["game_id", "player_id"], how="left")
    rep = pm["has_replay"] == 1
    pm["wpa"] = np.where(rep, pm["wpa_net"].fillna(0.0), 0.0)
    pm["wpa_rounds"] = np.where(rep, pm["rounds"], 0)
    rated = pm["rating"].notna()
    pm["rating_x_rounds"] = np.where(rated, pm["rating"] * pm["rounds"], 0.0)
    pm["rated_rounds"] = np.where(rated, pm["rounds"], 0)
    pm["acs_x_rounds"] = pm["acs"].fillna(0) * pm["rounds"]
    pm["kast_x_rounds"] = pm["kast_pct"].fillna(0) * pm["rounds"]
    pm["adr_x_rounds"] = pm["adr"].fillna(0) * pm["rounds"]
    pm["fk_minus_fd"] = pm["first_kills"].fillna(0) - pm["first_deaths"].fillna(0)
    for c in ("kills", "deaths"):
        pm[c] = pm[c].fillna(0)
    return pm, maps


def histories(pm, maps):
    """
    For each map, each lineup player's totals (num, den per stat) from earlier MATCHES only.
    Returns per map: team1/team2 player ids and their prior totals.
    """
    parts = sorted({c for pair in STATS.values() for c in pair})
    totals = defaultdict(lambda: np.zeros(len(parts)))
    lineups = {g: grp for g, grp in pm.groupby("game_id")}
    by_match = maps.groupby("match_id", sort=False)
    order = maps.drop_duplicates("match_id")[["match_id"]]
    out = []
    for match_id in order["match_id"]:
        mm = by_match.get_group(match_id)
        snapshot = {}
        for row in mm.itertuples(index=False):
            lu = lineups.get(row.game_id)
            if lu is None:
                continue
            t1 = lu[lu["team_id"] == row.team1_id]["player_id"].tolist()
            t2 = lu[lu["team_id"] == row.team2_id]["player_id"].tolist()
            prior = {p: snapshot.setdefault(p, totals[p].copy()) for p in t1 + t2}
            out.append((row.game_id, row.match_id, row.start_time, row.team1_id, row.team2_id,
                        int(row.winner_id == row.team1_id), t1, t2, prior))
        # Only after the whole match: add its maps to the players' records.
        for row in mm.itertuples(index=False):
            lu = lineups.get(row.game_id)
            if lu is None:
                continue
            for r in lu.itertuples(index=False):
                totals[r.player_id] += np.array([getattr(r, c) for c in parts], dtype=float)
    return out, parts


def elo_probs(maps, k):
    """Team Elo on map results, updated after each match (no leakage within a match)."""
    rating = defaultdict(lambda: 1500.0)
    probs = {}
    for match_id, mm in maps.groupby("match_id", sort=False):
        pending = []
        for row in mm.itertuples(index=False):
            ra, rb = rating[row.team1_id], rating[row.team2_id]
            p = 1 / (1 + 10 ** ((rb - ra) / 400))
            probs[row.game_id] = p
            pending.append((row.team1_id, row.team2_id, p, int(row.winner_id == row.team1_id)))
        for a, b, p, y in pending:
            rating[a] += k * (y - p)
            rating[b] -= k * (y - p)
    return probs


def team_feature(prior, players, parts, stat, k, league_mean):
    num_i, den_i = parts.index(STATS[stat][0]), parts.index(STATS[stat][1])
    vals = []
    for p in players:
        num, den = prior[p][num_i], prior[p][den_i]
        if stat == "K/D":
            # shrink kills and deaths toward the average K/D with k phantom deaths
            vals.append((num + k * league_mean) / (den + k))
        else:
            vals.append((num + k * league_mean) / (den + k))
    return np.mean(vals)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/vct.db")
    ap.add_argument("--test-from", default="2025-01-01")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    pm, maps = load(args.db)
    hist, parts = histories(pm, maps)
    league = {s: pm[STATS[s][0]].sum() / pm[STATS[s][1]].sum() for s in STATS}

    # Keep maps where all ten players have enough history in every stat (same maps for every stat).
    def enough(prior, players):
        return len(players) == 5 and all(
            prior[p][parts.index(STATS[s][1])] >= (MIN_PRIOR_ROUNDS if s != "K/D" else MIN_PRIOR_ROUNDS * 0.7)
            for p in players for s in STATS)
    rows = [h for h in hist if enough(h[8], h[6]) and enough(h[8], h[7])]
    df = pd.DataFrame([h[:6] for h in rows], columns=["game_id", "match_id", "start_time", "t1", "t2", "y"])
    train = (df["start_time"] < args.test_from).to_numpy()
    test = ~train
    print(f"{len(df):,} maps where all ten players have {MIN_PRIOR_ROUNDS}+ prior rounds in every stat "
          f"(train before {args.test_from}: {train.sum():,}, test: {test.sum():,})")

    y = df["y"].to_numpy()

    def feature(stat, k):
        return np.array([team_feature(h[8], h[6], parts, stat, k, league[stat])
                         - team_feature(h[8], h[7], parts, stat, k, league[stat]) for h in rows])

    def fit_score(X):
        X = X.reshape(len(y), -1)
        mu, sd = X[train].mean(0), X[train].std(0) + 1e-12
        Z = (X - mu) / sd
        m = LogisticRegression(C=1e6, max_iter=1000).fit(Z[train], y[train])
        return m.predict_proba(Z)[:, 1]

    # Tune each stat's shrinkage k on the training maps only.
    feats, chosen = {}, {}
    for stat in STATS:
        best = None
        for k in K_GRID:
            x = feature(stat, k)
            p = fit_score(x)
            ll = log_loss(y[train], p[train])
            if best is None or ll < best[0]:
                best = (ll, k, x)
        chosen[stat], feats[stat] = best[1], best[2]

    best_elo = None
    for k in ELO_K_GRID:
        pr = elo_probs(maps, k)
        e = np.array([pr[g] for g in df["game_id"]])
        x = np.log(e / (1 - e))
        ll = log_loss(y[train], fit_score(x)[train])
        if best_elo is None or ll < best_elo[0]:
            best_elo = (ll, k, x)
    elo_x = best_elo[2]

    preds = {"coin flip": np.full(len(y), 0.5), f"team Elo (K={best_elo[1]})": fit_score(elo_x)}
    for stat in STATS:
        preds[f"{stat} (k={chosen[stat]})"] = fit_score(feats[stat])
    preds["Elo + rating"] = fit_score(np.column_stack([elo_x, feats["vlr.gg rating"]]))
    preds["Elo + K/D"] = fit_score(np.column_stack([elo_x, feats["K/D"]]))
    preds["Elo + WPA"] = fit_score(np.column_stack([elo_x, feats[WPA]]))
    preds["rating + WPA"] = fit_score(np.column_stack([feats["vlr.gg rating"], feats[WPA]]))
    preds["Elo + rating + WPA"] = fit_score(np.column_stack([elo_x, feats["vlr.gg rating"], feats[WPA]]))

    # Paired bootstrap over test MATCHES for differences in log loss.
    test_df = df[test].reset_index(drop=True)
    matches = test_df["match_id"].unique()
    rng = np.random.default_rng(0)
    idx_by_match = test_df.groupby("match_id").indices
    boot = [np.concatenate([idx_by_match[m] for m in rng.choice(matches, len(matches))]) for _ in range(1000)]
    yt = y[test]

    def ll_rows(p):
        p = np.clip(p[test], 1e-9, 1 - 1e-9)
        return -(yt * np.log(p) + (1 - yt) * np.log(1 - p))

    rating_name = f"vlr.gg rating (k={chosen['vlr.gg rating']})"
    wpa_name = f"{WPA} (k={chosen[WPA]})"
    ref = {"vs rating": ll_rows(preds[rating_name]), "vs WPA": ll_rows(preds[wpa_name])}
    out = []
    for name, p in preds.items():
        r = ll_rows(p)
        row = {"model": name, "test log loss": r.mean(), "accuracy": np.mean((p[test] >= 0.5) == yt)}
        for label, base in ref.items():
            d = np.array([(r[b] - base[b]).mean() for b in boot])
            row[label] = f"{r.mean() - base.mean():+.4f} ({np.percentile(d, 2.5):+.4f}..{np.percentile(d, 97.5):+.4f})"
        out.append(row)
    t = pd.DataFrame(out).sort_values("test log loss")
    print(f"\nTest maps {args.test_from} on: lower log loss is better. Differences: negative = better than that model.")
    print(t.round(4).to_string(index=False))

    corr = pd.DataFrame({s: feats[s] for s in STATS}).corr().loc[WPA].drop(WPA).sort_values(ascending=False)
    print("\nCorrelation of the team-difference WPA feature with the other stats' features:")
    print("  " + ", ".join(f"{s} {v:.2f}" for s, v in corr.items()))


if __name__ == "__main__":
    main()
