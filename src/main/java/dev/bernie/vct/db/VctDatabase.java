package dev.bernie.vct.db;

import dev.bernie.vct.parse.MatchPage;
import dev.bernie.vct.parse.MatchPage.MapResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * The clean database (data/vct.db) that ratings and analysis read.
 *
 * It is always rebuilt from the saved HTML, never edited in place, so the
 * schema here can change freely: fix a parser, run build-db, done.
 * IDs are vlr.gg's own (match, game, event, team) so rows line up with the site.
 */
public class VctDatabase implements AutoCloseable {

    static final String[] SCHEMA = {
            """
            CREATE TABLE events (
                event_id   INTEGER PRIMARY KEY,
                name       TEXT    NOT NULL,
                year       INTEGER,
                type       TEXT    NOT NULL,  -- league | kickoff | masters | champions | lcq | qualifier
                region     TEXT    NOT NULL   -- Americas | EMEA | Pacific | China | International
            )""",
            """
            CREATE TABLE teams (
                team_id    INTEGER PRIMARY KEY,
                name       TEXT    NOT NULL   -- most recent name, sponsor prefix removed
            )""",
            """
            CREATE TABLE matches (
                match_id     INTEGER PRIMARY KEY,
                event_id     INTEGER NOT NULL REFERENCES events(event_id),
                stage        TEXT,             -- "Playoffs: Grand Final"
                start_time   TEXT,             -- ISO-8601 UTC, e.g. 2026-09-06T17:00:00Z
                patch        TEXT,             -- "13.04"; missing on some pages
                best_of      INTEGER,
                team1_id     INTEGER NOT NULL REFERENCES teams(team_id),
                team2_id     INTEGER NOT NULL REFERENCES teams(team_id),
                team1_score  INTEGER NOT NULL, -- maps won
                team2_score  INTEGER NOT NULL,
                winner_id    INTEGER REFERENCES teams(team_id),
                veto         TEXT,
                is_showmatch INTEGER NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE maps (
                game_id           INTEGER PRIMARY KEY,
                match_id          INTEGER NOT NULL REFERENCES matches(match_id),
                map_number        INTEGER NOT NULL,
                map_name          TEXT,
                picked_by_id      INTEGER REFERENCES teams(team_id),  -- NULL for the decider
                team1_score       INTEGER NOT NULL,                   -- rounds won
                team2_score       INTEGER NOT NULL,
                winner_id         INTEGER REFERENCES teams(team_id),
                team1_start_side  TEXT,                               -- atk | def
                -- Rounds by side. NULL on the rare pages where vlr.gg didn't record the split.
                team1_atk_rounds  INTEGER, team1_def_rounds INTEGER, team1_ot_rounds INTEGER,
                team2_atk_rounds  INTEGER, team2_def_rounds INTEGER, team2_ot_rounds INTEGER,
                duration_seconds  INTEGER
            )""",
            "CREATE INDEX idx_matches_start ON matches(start_time)",
            "CREATE INDEX idx_matches_team1 ON matches(team1_id)",
            "CREATE INDEX idx_matches_team2 ON matches(team2_id)",
            "CREATE INDEX idx_matches_event ON matches(event_id)",
            "CREATE INDEX idx_maps_match ON maps(match_id)",
            "CREATE INDEX idx_maps_name ON maps(map_name)",
            // One row per team per match, so "every match team X played" needs no OR.
            """
            CREATE VIEW team_matches AS
            SELECT m.match_id, m.start_time, m.event_id, m.is_showmatch,
                   m.team1_id AS team_id, m.team2_id AS opponent_id,
                   m.team1_score AS maps_won, m.team2_score AS maps_lost,
                   m.winner_id = m.team1_id AS won
            FROM matches m
            UNION ALL
            SELECT m.match_id, m.start_time, m.event_id, m.is_showmatch,
                   m.team2_id, m.team1_id, m.team2_score, m.team1_score,
                   m.winner_id = m.team2_id
            FROM matches m""",
    };

    private final Connection conn;
    private final PreparedStatement insertEvent;
    private final PreparedStatement upsertTeam;
    private final PreparedStatement insertMatch;
    private final PreparedStatement insertMap;

    /** Opens a fresh, empty database. The caller deletes any old file first. */
    public VctDatabase(String path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            for (String sql : SCHEMA) {
                st.execute(sql);
            }
        }
        conn.setAutoCommit(false); // one transaction for the whole build: much faster, all-or-nothing

        insertEvent = conn.prepareStatement(
                "INSERT OR IGNORE INTO events (event_id, name, year, type, region) VALUES (?, ?, ?, ?, ?)");
        upsertTeam = conn.prepareStatement(
                "INSERT INTO teams (team_id, name) VALUES (?, ?) "
                        + "ON CONFLICT(team_id) DO UPDATE SET name = excluded.name");
        insertMatch = conn.prepareStatement("""
                INSERT INTO matches (match_id, event_id, stage, start_time, patch, best_of,
                                     team1_id, team2_id, team1_score, team2_score, winner_id, veto, is_showmatch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
        insertMap = conn.prepareStatement("""
                INSERT INTO maps (game_id, match_id, map_number, map_name, picked_by_id,
                                  team1_score, team2_score, winner_id, team1_start_side,
                                  team1_atk_rounds, team1_def_rounds, team1_ot_rounds,
                                  team2_atk_rounds, team2_def_rounds, team2_ot_rounds, duration_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
    }

    /**
     * Writes a team's name. Call in chronological order so the latest name wins
     * (a team ID keeps its history through rebrands).
     */
    public void putTeam(MatchPage.Team team) throws SQLException {
        upsertTeam.setLong(1, team.id());
        upsertTeam.setString(2, team.name());
        upsertTeam.executeUpdate();
    }

    /** Inserts the match, its event (once), and its maps. Teams must already exist. */
    public void insertMatch(MatchPage p) throws SQLException {
        EventInfo info = EventInfo.of(p.eventName());
        insertEvent.setLong(1, p.eventId());
        insertEvent.setString(2, p.eventName());
        setNullableInt(insertEvent, 3, info.year());
        insertEvent.setString(4, info.type());
        insertEvent.setString(5, info.region());
        insertEvent.executeUpdate();

        long t1 = p.team1().id();
        long t2 = p.team2().id();
        insertMatch.setLong(1, p.matchId());
        insertMatch.setLong(2, p.eventId());
        insertMatch.setString(3, p.stage());
        insertMatch.setString(4, p.startTime() == null ? null : p.startTime().toString());
        insertMatch.setString(5, p.patch());
        setNullableInt(insertMatch, 6, p.bestOf() == 0 ? null : p.bestOf());
        insertMatch.setLong(7, t1);
        insertMatch.setLong(8, t2);
        insertMatch.setInt(9, p.team1Score());
        insertMatch.setInt(10, p.team2Score());
        setNullableLong(insertMatch, 11, winner(p.team1Score(), p.team2Score(), t1, t2));
        insertMatch.setString(12, p.veto());
        insertMatch.setInt(13, p.isShowmatch() ? 1 : 0);
        insertMatch.executeUpdate();

        for (MapResult m : p.maps()) {
            insertMap.setLong(1, m.gameId());
            insertMap.setLong(2, p.matchId());
            insertMap.setInt(3, m.mapNumber());
            insertMap.setString(4, m.mapName());
            setNullableLong(insertMap, 5, teamFor(m.pickedBy(), t1, t2));
            insertMap.setInt(6, m.team1Score());
            insertMap.setInt(7, m.team2Score());
            setNullableLong(insertMap, 8, winner(m.team1Score(), m.team2Score(), t1, t2));
            insertMap.setString(9, m.team1StartSide());
            setNullableInt(insertMap, 10, m.team1Atk());
            setNullableInt(insertMap, 11, m.team1Def());
            setNullableInt(insertMap, 12, m.team1Ot());
            setNullableInt(insertMap, 13, m.team2Atk());
            setNullableInt(insertMap, 14, m.team2Def());
            setNullableInt(insertMap, 15, m.team2Ot());
            setNullableInt(insertMap, 16, m.durationSeconds());
            insertMap.executeUpdate();
        }
    }

    private static Long winner(int score1, int score2, long t1, long t2) {
        return teamFor(score1 > score2 ? 1 : score2 > score1 ? 2 : 0, t1, t2);
    }

    /** 1 or 2 to that team's ID, anything else to null. Boxed on purpose: see the decider maps. */
    private static Long teamFor(int which, long t1, long t2) {
        if (which == 1) {
            return t1;
        }
        if (which == 2) {
            return t2;
        }
        return null;
    }

    private static void setNullableInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setLong(i, v);
        }
    }

    public Connection connection() {
        return conn;
    }

    public void commit() throws SQLException {
        conn.commit();
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
