"""Export the database to a small read-only SQLite file for the personal website's /vct/ pages.

Needs event_wpa (run python/wpa/event_wpa.py first). From the project root:
    .venv\\Scripts\\python python\\website\\export_site.py
    .venv\\Scripts\\python python\\website\\export_site.py --out ..\\PersonalWebsite\\vct\\data\\vct_site.sqlite

Probabilities and credits are stored as integers in basis points (1/10,000) to keep the file small:
5690 = 56.9%. The name avoids .sqlite3, which the website's .gitignore ignores.

Scopes: leaderboard and plays rows are computed for 'all' (every year together), each year ('y2026')
and each event with replay data ('e2976').

Tables:
  meta            key/value: years, counts, when it was built
  events, teams, players, matches, maps    every exported match (no showmatches)
  rounds          one row per round: who attacked, who won, attackers' win prob at round start
  actions         every kill/plant/defuse on replay maps: win prob before/after, credit to each player
  plays           best rounds, maps and matches by one player, per scope
  leaderboard     report.py's table per scope
"""
import argparse
import csv
import datetime
import os
import re
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # python/
from analysis.report import build  # noqa: E402  same numbers as report.py

BP = 10000  # basis points
TEAM_COLORS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "team_colors.csv")
# Words allowed in team_colors.csv, as shades that work on the site's light and dark backgrounds.
COLOR_WORDS = {
    "red": "#d7263d", "dark red": "#8f1d2c", "orange": "#f26b1d", "gold": "#d4a72c", "yellow": "#f5d000",
    "green": "#2e9e4f", "dark green": "#1d6b3a", "teal": "#0f9a9a", "blue": "#2563eb", "light blue": "#4fb3e8",
    "navy": "#1f2f6b", "purple": "#7c4dcc", "pink": "#ec5fa8", "magenta": "#c2268f", "brown": "#8a5a33",
    "black": "#111111", "white": "#f5f5f5", "grey": "#808080", "gray": "#808080", "silver": "#b8b8bd",
}

SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE events (event_id INTEGER PRIMARY KEY, name TEXT NOT NULL, year INTEGER, type TEXT, region TEXT,
                     first_match TEXT, last_match TEXT);
CREATE TABLE teams (team_id INTEGER PRIMARY KEY, name TEXT NOT NULL, tag TEXT,
                    primary_color TEXT, secondary_color TEXT);  -- #rrggbb from team_colors.csv, or NULL
CREATE TABLE players (player_id INTEGER PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE matches (match_id INTEGER PRIMARY KEY, event_id INTEGER NOT NULL, stage TEXT, start_time TEXT,
                      best_of INTEGER, team1_id INTEGER, team2_id INTEGER, team1_score INTEGER,
                      team2_score INTEGER, winner_id INTEGER);
CREATE TABLE maps (game_id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL, map_number INTEGER, map_name TEXT,
                   team1_score INTEGER, team2_score INTEGER, winner_id INTEGER, has_replay INTEGER);
CREATE TABLE rounds (game_id INTEGER NOT NULL, round_num INTEGER NOT NULL, attacker_id INTEGER,
                     winner_id INTEGER, outcome TEXT, team1_score INTEGER, team2_score INTEGER,
                     start_prob INTEGER,          -- attackers' win prob at round start (bp); NULL without replay
                     PRIMARY KEY (game_id, round_num)) WITHOUT ROWID;
CREATE TABLE actions (game_id INTEGER NOT NULL, round_num INTEGER NOT NULL, event_num INTEGER NOT NULL,
                      time_seconds INTEGER, type TEXT, action TEXT, actor_id INTEGER, victim_id INTEGER,
                      actor_team_id INTEGER, victim_team_id INTEGER, weapon TEXT, site TEXT,
                      prob_before INTEGER, prob_after INTEGER,   -- attackers' win prob (bp)
                      actor_credit INTEGER, victim_credit INTEGER,  -- WPA (bp), trade-adjusted
                      traded INTEGER,                -- this death was traded within the window
                      PRIMARY KEY (game_id, round_num, event_num)) WITHOUT ROWID;
CREATE TABLE player_maps (game_id INTEGER NOT NULL, player_id INTEGER NOT NULL, team_id INTEGER, agent TEXT,
                          rating REAL, acs INTEGER, kills INTEGER, deaths INTEGER, assists INTEGER,
                          kast_pct INTEGER, adr INTEGER, first_kills INTEGER, first_deaths INTEGER,
                          wpa INTEGER,                 -- player's total WPA on the map (bp); NULL without replay
                          PRIMARY KEY (game_id, player_id)) WITHOUT ROWID;
CREATE INDEX player_maps_player ON player_maps (player_id);
CREATE TABLE plays (scope TEXT NOT NULL,             -- all | y2026 | e2976
                    kind TEXT NOT NULL,              -- round | map | match
                    rank INTEGER NOT NULL, match_id INTEGER NOT NULL,
                    game_id INTEGER, round_num INTEGER, event_num INTEGER,  -- NULL where not applicable
                    player_id INTEGER, team_id INTEGER, credit INTEGER,     -- player's total WPA (bp)
                    kills INTEGER, deaths INTEGER, rounds INTEGER, rating REAL,
                    PRIMARY KEY (scope, kind, rank)) WITHOUT ROWID;
CREATE TABLE leaderboard (scope TEXT NOT NULL,       -- all | y2026 | e2976
                          player_id INTEGER NOT NULL, team TEXT, maps INTEGER, wpa_rounds INTEGER,
                          rating REAL, acs REAL, kd REAL, shrunk REAL, net REAL, low REAL, high REAL,
                          gained REAL, lost REAL,      -- % of a round win per round
                          PRIMARY KEY (scope, player_id)) WITHOUT ROWID;
CREATE INDEX matches_event ON matches (event_id, start_time);
CREATE INDEX maps_match ON maps (match_id, map_number);
"""


def export_plays(src, dst, scope, top):
    """Best rounds, maps and matches for the games in temp table gs."""
    wpa_bp = f"CAST(ROUND(wpa * {BP}) AS INTEGER)"
    queries = {
        # Best single rounds: one player's total credit in a round (clutches, multi-kills).
        "round": f"""
            WITH t AS (SELECT game_id, round_num, player_id, MAX(team_id) AS team_id, SUM(wpa) AS wpa,
                              SUM(kill) AS kills
                       FROM credit WHERE game_id IN gs GROUP BY game_id, round_num, player_id)
            SELECT ROW_NUMBER() OVER (ORDER BY wpa DESC), mp.match_id, t.game_id, t.round_num, NULL,
                   t.player_id, t.team_id, {wpa_bp}, t.kills, NULL, 1, NULL
            FROM t JOIN maps mp USING (game_id) ORDER BY wpa DESC LIMIT ?""",
        # Best maps and matches: a player's total credit, with their box score.
        "map": f"""
            SELECT ROW_NUMBER() OVER (ORDER BY wpa DESC), match_id, game_id, NULL, NULL, player_id, team_id,
                   {wpa_bp}, kills, deaths, rounds, rating
            FROM player_maps WHERE game_id IN gs ORDER BY wpa DESC LIMIT ?""",
        "match": f"""
            WITH t AS (SELECT match_id, player_id, MAX(team_id) AS team_id, SUM(wpa) AS wpa, SUM(kills) AS kills,
                              SUM(deaths) AS deaths, SUM(rounds) AS rounds,
                              SUM(rating * rounds) / SUM(CASE WHEN rating IS NOT NULL THEN rounds END) AS rating
                       FROM player_maps WHERE game_id IN gs GROUP BY match_id, player_id)
            SELECT ROW_NUMBER() OVER (ORDER BY wpa DESC), match_id, NULL, NULL, NULL, player_id, team_id,
                   {wpa_bp}, kills, deaths, rounds, ROUND(rating, 2)
            FROM t ORDER BY wpa DESC LIMIT ?""",
    }
    for kind, sql in queries.items():
        rows = [(scope, kind, *r) for r in src.execute(sql, (top,))]
        dst.executemany(f"INSERT INTO plays VALUES ({','.join('?' * 14)})", rows)


def color(value):
    """'#e4002b', 'e4002b' or a word from COLOR_WORDS -> '#e4002b'; None if blank or unknown."""
    v = (value or "").strip().lower()
    v = COLOR_WORDS.get(v, v)
    v = v if v.startswith("#") else "#" + v
    return v if re.fullmatch(r"#[0-9a-f]{6}", v) else None


def load_team_colors(dst):
    """Primary/secondary colors from team_colors.csv onto the exported teams. Bad values are skipped, with a warning."""
    if not os.path.exists(TEAM_COLORS):
        print("No team_colors.csv; teams get the default colors.")
        return
    with open(TEAM_COLORS, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            vals = [color(r.get(k)) for k in ("primary", "secondary")]
            for k, raw, v in zip(("primary", "secondary"), (r.get("primary"), r.get("secondary")), vals):
                if (raw or "").strip() and v is None:
                    print(f"team_colors.csv: {r.get('name')}: unknown {k} color {raw!r} (use #rrggbb or one of: "
                          f"{', '.join(COLOR_WORDS)})")
            dst.execute("UPDATE teams SET primary_color = ?, secondary_color = ? WHERE team_id = ?",
                        (*vals, int(r["team_id"])))


def export(src, out, years, top_plays):
    if os.path.exists(out):
        os.remove(out)
    dst = sqlite3.connect(out)
    dst.executescript(SCHEMA)
    marks = ",".join("?" * len(years))
    src.execute(f"""CREATE TEMP TABLE g AS SELECT mp.game_id, e.year, e.event_id FROM maps mp JOIN matches m USING (match_id)
                    JOIN events e USING (event_id) WHERE e.year IN ({marks}) AND NOT m.is_showmatch""", years)
    src.execute("CREATE INDEX temp.g_id ON g (game_id)")

    def copy(table, sql, params=()):
        rows = src.execute(sql, params).fetchall()
        if rows:
            dst.executemany(f"INSERT INTO {table} VALUES ({','.join('?' * len(rows[0]))})", rows)
        return len(rows)

    copy("events", f"""SELECT e.event_id, e.name, e.year, e.type, e.region, MIN(m.start_time), MAX(m.start_time)
                       FROM events e JOIN matches m USING (event_id)
                       WHERE e.year IN ({marks}) AND NOT m.is_showmatch GROUP BY e.event_id""", years)
    n_matches = copy("matches", f"""SELECT match_id, event_id, stage, start_time, best_of, team1_id, team2_id,
                                           team1_score, team2_score, winner_id
                                    FROM matches m JOIN events e USING (event_id)
                                    WHERE e.year IN ({marks}) AND NOT m.is_showmatch""", years)
    n_maps = copy("maps", """SELECT game_id, match_id, map_number, map_name, team1_score, team2_score, winner_id,
                                    has_replay FROM maps WHERE game_id IN (SELECT game_id FROM g)""")
    n_rounds = copy("rounds", f"""SELECT r.game_id, r.round_num, r.attacker_id, r.winner_id, r.outcome,
                                         r.team1_score, r.team2_score, CAST(ROUND(w.start_prob * {BP}) AS INTEGER)
                                  FROM rounds r LEFT JOIN round_wpa w USING (game_id, round_num)
                                  WHERE r.game_id IN (SELECT game_id FROM g)""")
    n_actions = copy("actions", f"""
        SELECT w.game_id, w.round_num, w.event_num, w.time_seconds, w.type, w.action, w.actor_id, w.victim_id,
               w.actor_team_id, w.victim_team_id, w.weapon, e.site,
               CAST(ROUND(w.prob_before * {BP}) AS INTEGER), CAST(ROUND(w.prob_after * {BP}) AS INTEGER),
               CAST(ROUND(w.actor_credit * {BP}) AS INTEGER), CAST(ROUND(w.victim_credit * {BP}) AS INTEGER),
               w.traded_by_event IS NOT NULL
        FROM event_wpa w JOIN replay_events e USING (game_id, round_num, event_num)
        WHERE w.game_id IN (SELECT game_id FROM g)""")
    copy("teams", """SELECT team_id, name, tag, NULL, NULL FROM teams WHERE team_id IN
                     (SELECT team1_id FROM matches m JOIN maps mp USING (match_id) WHERE mp.game_id IN (SELECT game_id FROM g)
                      UNION SELECT team2_id FROM matches m JOIN maps mp USING (match_id) WHERE mp.game_id IN (SELECT game_id FROM g))""")
    load_team_colors(dst)
    copy("players", """SELECT player_id, name FROM players WHERE player_id IN
                       (SELECT player_id FROM player_map_stats WHERE game_id IN (SELECT game_id FROM g))""")

    # Every credit to a player, one row per kill/death they were part of; then per player per map.
    src.execute("""CREATE TEMP TABLE credit AS
        SELECT game_id, round_num, actor_id AS player_id, actor_team_id AS team_id, actor_credit AS wpa,
               type = 'kill' AND actor_team_id != victim_team_id AS kill
        FROM event_wpa WHERE game_id IN (SELECT game_id FROM g) AND actor_id IS NOT NULL AND actor_credit IS NOT NULL
        UNION ALL
        SELECT game_id, round_num, victim_id, victim_team_id, victim_credit, 0
        FROM event_wpa WHERE game_id IN (SELECT game_id FROM g) AND victim_id IS NOT NULL AND victim_credit IS NOT NULL""")
    src.execute("CREATE INDEX temp.credit_game ON credit (game_id)")
    src.execute("""CREATE TEMP TABLE player_maps AS
        SELECT c.game_id, mp.match_id, c.player_id, s.team_id, SUM(c.wpa) AS wpa, s.kills, s.deaths, s.rating,
               mp.team1_score + mp.team2_score AS rounds
        FROM credit c JOIN maps mp USING (game_id)
        JOIN player_map_stats s ON s.game_id = c.game_id AND s.player_id = c.player_id AND s.side = 'both'
        GROUP BY c.game_id, c.player_id""")

    # Every player's box score on every map, with their WPA where the map has a replay.
    copy("player_maps", f"""
        SELECT s.game_id, s.player_id, s.team_id, s.agent, s.rating, s.acs, s.kills, s.deaths, s.assists,
               s.kast_pct, s.adr, s.first_kills, s.first_deaths,
               CASE WHEN mp.has_replay THEN CAST(ROUND(COALESCE(c.wpa, 0) * {BP}) AS INTEGER) END
        FROM player_map_stats s JOIN maps mp USING (game_id)
        LEFT JOIN (SELECT game_id, player_id, SUM(wpa) AS wpa FROM credit GROUP BY game_id, player_id) c
               ON c.game_id = s.game_id AND c.player_id = s.player_id
        WHERE s.side = 'both' AND s.game_id IN (SELECT game_id FROM g)""")

    replay_events = [i for (i,) in src.execute(
        "SELECT DISTINCT g.event_id FROM g JOIN maps USING (game_id) WHERE maps.has_replay")]
    play_scopes = ([("all", "", ())] + [(f"y{y}", " WHERE year = ?", (y,)) for y in years]
                   + [(f"e{i}", " WHERE event_id = ?", (i,)) for i in replay_events])
    for scope, where, params in play_scopes:
        src.execute("DROP TABLE IF EXISTS temp.gs")
        src.execute("CREATE TEMP TABLE gs (game_id INTEGER PRIMARY KEY)")
        src.execute("INSERT INTO gs SELECT game_id FROM g" + where, params)
        export_plays(src, dst, scope, top_plays)

    # Leaderboards. Events without replay maps (China) have no WPA, so no leaderboard.
    cols = ["player_id", "team", "maps", "wpa_rounds", "rating", "acs", "kd", "shrunk", "net", "low", "high",
            "gained", "lost"]
    scopes = [("all", None, None)] + [(f"y{y}", y, None) for y in years] + [
        (f"e{i}", None, n) for i, n in dst.execute(
            "SELECT event_id, name FROM events WHERE event_id IN "
            "(SELECT event_id FROM matches JOIN maps USING (match_id) WHERE has_replay)")]
    for scope, year, event in scopes:  # 'all' = no filter: every year in the database
        d, *_ = build(src, year, event)
        d = d[d["wpa_rounds"].fillna(0) > 0][cols].round(3)
        rows = [(scope, *(None if v != v else v for v in r)) for r in d.itertuples(index=False)]
        dst.executemany(f"INSERT INTO leaderboard VALUES ({','.join('?' * (len(cols) + 1))})", rows)

    meta = {"years": ",".join(map(str, years)), "matches": n_matches, "maps": n_maps, "rounds": n_rounds,
            "actions": n_actions, "built": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")}
    dst.executemany("INSERT INTO meta VALUES (?, ?)", [(k, str(v)) for k, v in meta.items()])
    dst.commit()
    dst.execute("VACUUM")
    dst.close()
    return meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/vct.db")
    ap.add_argument("--out", default="data/exports/vct_site.sqlite")
    ap.add_argument("--top-plays", type=int, default=100)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    with sqlite3.connect(f"file:{args.db}?mode=ro", uri=True) as src:
        if not src.execute("SELECT 1 FROM sqlite_master WHERE name = 'event_wpa'").fetchone():
            sys.exit("No event_wpa table: run python/wpa/event_wpa.py first.")
        years = [y for (y,) in src.execute("SELECT DISTINCT year FROM events WHERE year IS NOT NULL ORDER BY year")]
        meta = export(src, args.out, years, args.top_plays)
    size = os.path.getsize(args.out) / 1e6
    print(f"{meta['years']}: {meta['matches']} matches, {meta['maps']} maps, {meta['rounds']} rounds, "
          f"{meta['actions']} actions -> {args.out} ({size:.1f} MB)")


if __name__ == "__main__":
    main()
