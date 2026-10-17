"""Train the round win-probability model behind WPA, and score every game state.

Reads game_states_full from vct.db (built by `gradlew run --args="build-db"`), compares
three models on a held-out season, then writes an attacker win probability for every
state (state_win_prob) and for the moment just before every action (event_prob_before).
model/event_wpa.py turns those into credit per player.

Run from the project root:
    .venv\\Scripts\\python model\\train_wpa.py
    .venv\\Scripts\\python model\\train_wpa.py --db data\\vct.db --test-from 2026-01-01

build-db recreates vct.db from scratch, so run this again after every build-db.
"""
import argparse
import sqlite3
import time
import warnings

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score
from sklearn.model_selection import GroupKFold, GroupShuffleSplit
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

# LightGBM 4.7 renamed eval_set; the old name still works and keeps older versions supported.
warnings.filterwarnings("ignore", message=".*'eval_set' is deprecated.*")

ROUND_SECONDS = 100   # buy phase excluded: the clock starts when the barriers drop
SPIKE_SECONDS = 45

NUMERIC = [
    "atk_alive", "def_alive", "alive_diff", "spike_planted",
    "time_seconds", "round_time_left", "spike_time_left",
    "atk_loadout", "def_loadout", "loadout_diff", "atk_start_loadout", "def_start_loadout",
    "atk_bank", "def_bank",
    "atk_rifles", "def_rifles", "atk_snipers", "def_snipers", "atk_heavy_armor", "def_heavy_armor",
    "atk_score", "def_score", "round_num", "year",
]
CATEGORICAL = ["map_name", "plant_site"]
FEATURES = NUMERIC + CATEGORICAL

# Things that can only help the side that has more of them.
MONOTONE = {"atk_alive": 1, "def_alive": -1, "alive_diff": 1,
            "atk_loadout": 1, "def_loadout": -1, "loadout_diff": 1}

LGB_PARAMS = dict(objective="binary", learning_rate=0.05, num_leaves=63, min_child_samples=200,
                  subsample=0.8, subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0,
                  n_estimators=3000, verbose=-1)


def load(db: str) -> pd.DataFrame:
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        df = pd.read_sql_query("SELECT * FROM game_states_full", conn)
    df["start_time"] = pd.to_datetime(df["start_time"], utc=True)
    df["plant_site"] = df["plant_site"].fillna("none")
    for col in CATEGORICAL:
        df[col] = df[col].astype("category")
    return derive(df)


def derive(df: pd.DataFrame) -> pd.DataFrame:
    """Features computed from the stored columns. Also used for the 'just before' rows."""
    df["year"] = df["start_time"].dt.year
    df["alive_diff"] = df["atk_alive"] - df["def_alive"]
    df["loadout_diff"] = df["atk_loadout"] - df["def_loadout"]
    planted = df["spike_planted"] == 1
    df["round_time_left"] = np.where(planted, 0, (ROUND_SECONDS - df["time_seconds"]).clip(lower=0))
    df["spike_time_left"] = np.where(planted, (SPIKE_SECONDS - df["seconds_since_plant"].fillna(0)).clip(lower=0), 0)
    return df


def just_before(db: str, df: pd.DataFrame) -> pd.DataFrame:
    """
    For each action, the state the action happened in: the previous state with the clock moved
    on to the action's time. Scoring these splits each change in win probability into the clock's
    part (previous state -> just before) and the action's part (just before -> after).

    Covers every action that produced a state, plus the action that ended each round
    (the wiping kill or the defuse), which game_states doesn't store a state for.
    """
    keys = ["game_id", "round_num"]
    d = df.sort_values(keys + ["state_num"]).reset_index(drop=True)

    # Actions that led to a state: the previous state, at this state's time.
    prev = d.groupby(keys, sort=False).shift(1)
    has_prev = d["state_num"] > 0
    a = prev[has_prev].copy()
    a[keys] = d.loc[has_prev, keys]
    a["event_num"] = d.loc[has_prev, "event_num"].to_numpy()
    a["new_time"] = d.loc[has_prev, "time_seconds"].to_numpy()

    # The round-ending action: the first kill or defuse after the last state.
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        ev = pd.read_sql_query("""SELECT game_id, round_num, event_num, type, time_seconds
            FROM replay_events WHERE type IN ('kill', 'defuse') AND after_detonation = 0
            AND time_seconds IS NOT NULL""", conn)
    last = d.groupby(keys, sort=False).tail(1)
    m = ev.merge(last[keys + ["event_num"]].rename(columns={"event_num": "last_event"}), on=keys)
    m = m[m["event_num"] > m["last_event"].fillna(-1)]
    ending = m.sort_values("event_num").groupby(keys, sort=False).head(1)
    b = last.drop(columns=["event_num"]).merge(ending[keys + ["event_num", "time_seconds"]]
                                               .rename(columns={"time_seconds": "new_time"}), on=keys)

    out = pd.concat([a, b], ignore_index=True)
    out["new_time"] = np.maximum(out["new_time"], out["time_seconds"])
    planted = out["spike_planted"] == 1
    out.loc[planted, "seconds_since_plant"] += out.loc[planted, "new_time"] - out.loc[planted, "time_seconds"]
    out["time_seconds"] = out["new_time"]
    out["event_num"] = out["event_num"].astype(int)
    for col in CATEGORICAL:
        out[col] = pd.Categorical(out[col], categories=df[col].cat.categories)
    return derive(out)


def metrics(y, p) -> dict:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return {"log loss": log_loss(y, p), "Brier": brier_score_loss(y, p),
            "accuracy": np.mean((p >= 0.5) == y), "AUC": roc_auc_score(y, p)}


def calibration(y, p, bins=10) -> pd.DataFrame:
    edges = np.linspace(0, 1, bins + 1)
    idx = np.clip(np.digitize(p, edges) - 1, 0, bins - 1)
    t = pd.DataFrame({"bin": idx, "y": y, "p": p}).groupby("bin").agg(
        predicted=("p", "mean"), actual=("y", "mean"), states=("y", "size"))
    t.index = [f"{edges[i]:.1f}-{edges[i + 1]:.1f}" for i in t.index]
    return t


class AliveSpikeTable:
    """Baseline: the win rate for each (attackers alive, defenders alive, spike) combination."""

    KEY = ["atk_alive", "def_alive", "spike_planted"]

    def fit(self, X, y):
        d = X[self.KEY].assign(y=y.values)
        self.rates = d.groupby(self.KEY)["y"].mean()
        self.prior = y.mean()
        return self

    def predict(self, X):
        keys = pd.MultiIndex.from_frame(X[self.KEY])
        return self.rates.reindex(keys).fillna(self.prior).to_numpy()


def logistic():
    pre = ColumnTransformer([
        ("num", StandardScaler(), NUMERIC),
        ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL),
    ])
    return make_pipeline(pre, LogisticRegression(max_iter=2000, C=1.0))


def lightgbm(n_estimators=None):
    params = dict(LGB_PARAMS)
    if n_estimators:
        params["n_estimators"] = n_estimators
    params["monotone_constraints"] = [MONOTONE.get(f, 0) for f in FEATURES]
    return lgb.LGBMClassifier(**params)


def fit_lightgbm_early_stopping(train: pd.DataFrame):
    """Hold out 15% of training matches to pick the number of trees."""
    split = GroupShuffleSplit(n_splits=1, test_size=0.15, random_state=0)
    fit_idx, val_idx = next(split.split(train, groups=train["match_id"]))
    fit, val = train.iloc[fit_idx], train.iloc[val_idx]
    model = lightgbm()
    model.fit(fit[FEATURES], fit["atk_won"], eval_set=[(val[FEATURES], val["atk_won"])],
              callbacks=[lgb.early_stopping(100, verbose=False)])
    return model.best_iteration_


def out_of_fold(df: pd.DataFrame, before: pd.DataFrame, n_estimators: int, folds=5):
    """Every state (and every 'just before' row) scored by a model that never saw its match."""
    p = np.zeros(len(df))
    pb = np.zeros(len(before))
    for i, (tr, te) in enumerate(GroupKFold(n_splits=folds).split(df, groups=df["match_id"])):
        m = lightgbm(n_estimators).fit(df.iloc[tr][FEATURES], df.iloc[tr]["atk_won"])
        p[te] = m.predict_proba(df.iloc[te][FEATURES])[:, 1]
        in_fold = before["match_id"].isin(df.iloc[te]["match_id"].unique()).to_numpy()
        if in_fold.any():
            pb[in_fold] = m.predict_proba(before.loc[in_fold, FEATURES])[:, 1]
        print(f"  fold {i + 1}/{folds} done")
    return p, pb


def write_before(db: str, before: pd.DataFrame, probs: np.ndarray) -> None:
    rows = list(zip(before["game_id"].astype(int), before["round_num"].astype(int),
                    before["event_num"].astype(int), probs.astype(float)))
    with sqlite3.connect(db) as conn:
        conn.execute("DROP TABLE IF EXISTS event_prob_before")
        conn.execute("""
            CREATE TABLE event_prob_before (
                game_id      INTEGER NOT NULL,
                round_num    INTEGER NOT NULL,
                event_num    INTEGER NOT NULL,  -- replay_events row
                atk_win_prob REAL    NOT NULL,  -- just before that action, at its time
                PRIMARY KEY (game_id, round_num, event_num)
            ) WITHOUT ROWID""")
        conn.executemany("INSERT INTO event_prob_before VALUES (?, ?, ?, ?)", rows)


def write_probs(db: str, df: pd.DataFrame, probs: np.ndarray) -> None:
    rows = list(zip(df["game_id"].astype(int), df["round_num"].astype(int),
                    df["state_num"].astype(int), probs.astype(float)))
    with sqlite3.connect(db) as conn:
        conn.execute("DROP TABLE IF EXISTS state_win_prob")
        conn.execute("""
            CREATE TABLE state_win_prob (
                game_id      INTEGER NOT NULL,
                round_num    INTEGER NOT NULL,
                state_num    INTEGER NOT NULL,
                atk_win_prob REAL    NOT NULL,  -- out-of-fold LightGBM: its match was never trained on
                PRIMARY KEY (game_id, round_num, state_num),
                FOREIGN KEY (game_id, round_num, state_num)
                    REFERENCES game_states(game_id, round_num, state_num)
            ) WITHOUT ROWID""")
        conn.executemany("INSERT INTO state_win_prob VALUES (?, ?, ?, ?)", rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/vct.db")
    ap.add_argument("--test-from", default="2026-01-01", help="matches from this date on are the test set")
    ap.add_argument("--no-write", action="store_true", help="evaluate only; don't write state_win_prob")
    args = ap.parse_args()

    started = time.time()
    df = load(args.db)
    cutoff = pd.Timestamp(args.test_from, tz="UTC")
    train, test = df[df["start_time"] < cutoff], df[df["start_time"] >= cutoff]
    print(f"{len(df):,} states from {df['match_id'].nunique():,} matches; "
          f"train {len(train):,} (before {args.test_from}), test {len(test):,}")

    trees = fit_lightgbm_early_stopping(train)
    print(f"LightGBM: {trees} trees chosen on a validation split")

    models = {
        "alive + spike table": AliveSpikeTable().fit(train, train["atk_won"]),
        "logistic regression": logistic().fit(train[FEATURES], train["atk_won"]),
        "LightGBM": lightgbm(trees).fit(train[FEATURES], train["atk_won"]),
    }
    preds = {}
    for name, m in models.items():
        preds[name] = m.predict(test) if isinstance(m, AliveSpikeTable) else m.predict_proba(test[FEATURES])[:, 1]

    y = test["atk_won"].to_numpy()
    start = (test["state_num"] == 0).to_numpy()
    print(f"\nTest season ({args.test_from} on): all in-round states")
    print(pd.DataFrame({n: metrics(y, p) for n, p in preds.items()}).T.round(4).to_string())
    print("\nTest season: round start only (what pre-round models like Wang 2025 predict)")
    print(pd.DataFrame({n: metrics(y[start], p[start]) for n, p in preds.items()}).T.round(4).to_string())

    print("\nLightGBM calibration on the test season (predicted vs actual attacker win rate):")
    print(calibration(y, preds["LightGBM"]).round(3).to_string())

    imp = pd.Series(models["LightGBM"].booster_.feature_importance("gain"), index=FEATURES)
    print("\nLightGBM feature importance (share of total gain):")
    print((imp / imp.sum()).sort_values(ascending=False).head(12).round(3).to_string())

    if not args.no_write:
        before = just_before(args.db, df)
        print(f"\nScoring all {len(df):,} states and {len(before):,} 'just before an action' rows "
              "out-of-fold (5 folds, grouped by match)...")
        probs, probs_before = out_of_fold(df, before, trees)
        print("Out-of-fold, all seasons:", {k: round(float(v), 4)
                                            for k, v in metrics(df["atk_won"].to_numpy(), probs).items()})
        write_probs(args.db, df, probs)
        write_before(args.db, before, probs_before)
        print(f"Wrote state_win_prob and event_prob_before to {args.db}. Next: model\\event_wpa.py")
    print(f"Done in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
