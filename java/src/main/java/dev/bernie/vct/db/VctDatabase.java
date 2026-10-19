package dev.bernie.vct.db;

import dev.bernie.vct.parse.MatchPage;
import dev.bernie.vct.parse.MatchPage.MapResult;
import dev.bernie.vct.parse.MatchPage.PlayerStats;
import dev.bernie.vct.parse.MatchPage.RoundResult;
import dev.bernie.vct.parse.MatchPage.SideStats;
import dev.bernie.vct.parse.ReplayPage;
import dev.bernie.vct.parse.ReplayPage.Event;
import dev.bernie.vct.parse.ReplayPage.Loadout;
import dev.bernie.vct.parse.ReplayPage.Position;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The clean database (data/vct.db) that ratings and analysis read.
 *
 * It is always rebuilt from the saved HTML, never edited in place, so the
 * schema here can change freely: fix a parser, run build-db, done.
 * IDs are vlr.gg's own (match, game, event, team, player) so rows line up with the site.
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
                name       TEXT    NOT NULL,  -- most recent name, sponsor prefix removed
                tag        TEXT               -- most recent tag, e.g. 100T
            )""",
            """
            CREATE TABLE players (
                player_id  INTEGER PRIMARY KEY,
                name       TEXT    NOT NULL,  -- vlr.gg shows a player's current handle on every match
                country    TEXT
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
                duration_seconds  INTEGER,
                has_replay        INTEGER NOT NULL DEFAULT 0          -- replay tables have rows for it
            )""",
            """
            CREATE TABLE player_map_stats (
                game_id      INTEGER NOT NULL REFERENCES maps(game_id),
                player_id    INTEGER NOT NULL REFERENCES players(player_id),
                team_id      INTEGER NOT NULL REFERENCES teams(team_id),
                side         TEXT    NOT NULL,  -- both | atk | def  (three rows per player per map)
                agent        TEXT,
                rating       REAL,              -- vlr.gg Rating 2.0
                acs          INTEGER,
                kills        INTEGER, deaths INTEGER, assists INTEGER,
                kast_pct     INTEGER,
                adr          INTEGER,
                hs_pct       INTEGER,
                first_kills  INTEGER, first_deaths INTEGER,
                PRIMARY KEY (game_id, player_id, side)
            )""",
            """
            CREATE TABLE rounds (
                game_id         INTEGER NOT NULL REFERENCES maps(game_id),
                round_num       INTEGER NOT NULL,
                winner_id       INTEGER NOT NULL REFERENCES teams(team_id),
                winner_side     TEXT,              -- atk | def
                attacker_id     INTEGER REFERENCES teams(team_id),
                outcome         TEXT,              -- elim | boom (spike detonated) | defuse | time
                team1_score     INTEGER NOT NULL,  -- running score after this round
                team2_score     INTEGER NOT NULL,
                PRIMARY KEY (game_id, round_num)
            )""",
            """
            CREATE TABLE round_economy (
                game_id         INTEGER NOT NULL REFERENCES maps(game_id),
                round_num       INTEGER NOT NULL,
                player_id       INTEGER NOT NULL REFERENCES players(player_id),
                team_id         INTEGER NOT NULL REFERENCES teams(team_id),
                side            TEXT,              -- atk | def
                agent           TEXT,
                credits_spent   INTEGER,           -- in the buy phase
                credits_left    INTEGER,           -- after the buy phase
                armor           TEXT,              -- heavy | light | regen | none
                weapon          TEXT,              -- main gun held at round start
                PRIMARY KEY (game_id, round_num, player_id)
            )""",
            """
            CREATE TABLE replay_events (
                game_id          INTEGER NOT NULL REFERENCES maps(game_id),
                round_num        INTEGER NOT NULL,
                event_num        INTEGER NOT NULL,  -- order within the round, from 0
                time_seconds     INTEGER,           -- into the round
                type             TEXT    NOT NULL,  -- kill | plant | defuse
                actor_id         INTEGER REFERENCES players(player_id),  -- killer / planter / defuser;
                                                                         -- NULL for spike/fall deaths
                victim_id        INTEGER REFERENCES players(player_id),  -- kills only
                actor_team_id    INTEGER REFERENCES teams(team_id),      -- from the round roster
                victim_team_id   INTEGER REFERENCES teams(team_id),      -- same team as actor = teamkill
                weapon           TEXT,              -- kills: weapon or ability, e.g. Vandal, Blade Storm
                site             TEXT,              -- plants/defuses: A | B | C
                x REAL, y REAL,                     -- kill: victim; plant/defuse: actor
                from_x REAL, from_y REAL,           -- kill: killer (NULL for spike/fall deaths)
                after_detonation INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (game_id, round_num, event_num)
            )""",
            """
            CREATE TABLE replay_positions (
                game_id      INTEGER NOT NULL,
                round_num    INTEGER NOT NULL,
                event_num    INTEGER NOT NULL,
                player_id    INTEGER NOT NULL REFERENCES players(player_id),
                x REAL NOT NULL, y REAL NOT NULL,  -- replay-map pixels; each map has its own layout
                facing       INTEGER,              -- view angle in degrees
                PRIMARY KEY (game_id, round_num, event_num, player_id),
                FOREIGN KEY (game_id, round_num, event_num) REFERENCES replay_events(game_id, round_num, event_num)
            ) WITHOUT ROWID""",
            """
            CREATE TABLE game_states (
                -- WPA training data: round start, then after each kill/plant until the round is
                -- decided. Attacker's point of view. Loadouts and counts cover living players.
                game_id             INTEGER NOT NULL REFERENCES maps(game_id),
                round_num           INTEGER NOT NULL,
                state_num           INTEGER NOT NULL,  -- 0 = round start
                event_num           INTEGER,           -- replay_events row that led here; NULL at start
                time_seconds        INTEGER NOT NULL,  -- into the round
                attacker_id         INTEGER NOT NULL REFERENCES teams(team_id),
                defender_id         INTEGER NOT NULL REFERENCES teams(team_id),
                atk_alive           INTEGER NOT NULL,
                def_alive           INTEGER NOT NULL,
                spike_planted       INTEGER NOT NULL,
                plant_site          TEXT,
                seconds_since_plant INTEGER,
                atk_loadout         INTEGER NOT NULL,  -- credits of gear held (see Prices.java)
                def_loadout         INTEGER NOT NULL,
                atk_start_loadout   INTEGER NOT NULL,  -- all five at round start
                def_start_loadout   INTEGER NOT NULL,
                atk_bank            INTEGER NOT NULL,  -- credits at buy time (spent + left, capped)
                def_bank            INTEGER NOT NULL,
                atk_rifles          INTEGER NOT NULL, def_rifles  INTEGER NOT NULL,
                atk_snipers         INTEGER NOT NULL, def_snipers INTEGER NOT NULL,
                atk_heavy_armor     INTEGER NOT NULL, def_heavy_armor INTEGER NOT NULL,
                atk_score           INTEGER NOT NULL,  -- rounds won before this round
                def_score           INTEGER NOT NULL,
                atk_won             INTEGER NOT NULL,  -- label
                PRIMARY KEY (game_id, round_num, state_num)
            ) WITHOUT ROWID""",
            // Training rows with the context a model wants (map, date, patch, event).
            """
            CREATE VIEW game_states_full AS
            SELECT g.*, mp.match_id, mp.map_name, m.start_time, m.patch, e.type AS event_type, e.region
            FROM game_states g
            JOIN maps mp ON mp.game_id = g.game_id
            JOIN matches m ON m.match_id = mp.match_id
            JOIN events e ON e.event_id = m.event_id
            WHERE NOT m.is_showmatch""",
            "CREATE INDEX idx_matches_start ON matches(start_time)",
            "CREATE INDEX idx_matches_team1 ON matches(team1_id)",
            "CREATE INDEX idx_matches_team2 ON matches(team2_id)",
            "CREATE INDEX idx_matches_event ON matches(event_id)",
            "CREATE INDEX idx_maps_match ON maps(match_id)",
            "CREATE INDEX idx_maps_name ON maps(map_name)",
            "CREATE INDEX idx_pms_player ON player_map_stats(player_id)",
            "CREATE INDEX idx_pms_team ON player_map_stats(team_id)",
            "CREATE INDEX idx_econ_player ON round_economy(player_id)",
            "CREATE INDEX idx_events_actor ON replay_events(actor_id)",
            "CREATE INDEX idx_events_victim ON replay_events(victim_id)",
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
            // Whole-map player lines with names and context, ready for pandas or a quick query.
            """
            CREATE VIEW player_maps_full AS
            SELECT s.game_id, mp.match_id, m.start_time, e.name AS event, e.type AS event_type,
                   e.region, mp.map_name, p.player_id, p.name AS player, t.team_id, t.name AS team,
                   CASE WHEN t.team_id = m.team1_id THEN m.team2_id ELSE m.team1_id END AS opponent_id,
                   mp.winner_id = t.team_id AS won_map, s.agent, s.rating, s.acs, s.kills, s.deaths,
                   s.assists, s.kast_pct, s.adr, s.hs_pct, s.first_kills, s.first_deaths
            FROM player_map_stats s
            JOIN maps mp ON mp.game_id = s.game_id
            JOIN matches m ON m.match_id = mp.match_id
            JOIN events e ON e.event_id = m.event_id
            JOIN players p ON p.player_id = s.player_id
            JOIN teams t ON t.team_id = s.team_id
            WHERE s.side = 'both' AND NOT m.is_showmatch""",
    };

    private final Connection conn;
    private final PreparedStatement insertEvent;
    private final PreparedStatement upsertTeam;
    private final PreparedStatement upsertPlayer;
    private final PreparedStatement insertMatch;
    private final PreparedStatement insertMap;
    private final PreparedStatement insertStats;
    private final PreparedStatement insertRound;
    private final PreparedStatement markReplay;
    private final PreparedStatement insertEconomy;
    private final PreparedStatement insertReplayEvent;
    private final PreparedStatement insertPosition;
    private final PreparedStatement insertState;

    /** Opens a fresh, empty database. The caller deletes any old file first. */
    public VctDatabase(String path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = OFF");   // a failed build is thrown away anyway
            st.execute("PRAGMA synchronous = OFF");
            for (String sql : SCHEMA) {
                st.execute(sql);
            }
        }
        conn.setAutoCommit(false); // one transaction for the whole build: much faster, all-or-nothing

        insertEvent = conn.prepareStatement(
                "INSERT OR IGNORE INTO events (event_id, name, year, type, region) VALUES (?, ?, ?, ?, ?)");
        upsertTeam = conn.prepareStatement("""
                INSERT INTO teams (team_id, name, tag) VALUES (?, ?, ?)
                ON CONFLICT(team_id) DO UPDATE SET name = excluded.name, tag = COALESCE(excluded.tag, teams.tag)""");
        upsertPlayer = conn.prepareStatement("""
                INSERT INTO players (player_id, name, country) VALUES (?, ?, ?)
                ON CONFLICT(player_id) DO UPDATE SET name = excluded.name,
                    country = COALESCE(excluded.country, players.country)""");
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
        insertStats = conn.prepareStatement("""
                INSERT INTO player_map_stats (game_id, player_id, team_id, side, agent, rating, acs,
                                              kills, deaths, assists, kast_pct, adr, hs_pct,
                                              first_kills, first_deaths)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
        insertRound = conn.prepareStatement("""
                INSERT INTO rounds (game_id, round_num, winner_id, winner_side, attacker_id, outcome,
                                    team1_score, team2_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""");
        markReplay = conn.prepareStatement("UPDATE maps SET has_replay = 1 WHERE game_id = ?");
        insertEconomy = conn.prepareStatement("""
                INSERT INTO round_economy (game_id, round_num, player_id, team_id, side, agent,
                                           credits_spent, credits_left, armor, weapon)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
        insertReplayEvent = conn.prepareStatement("""
                INSERT INTO replay_events (game_id, round_num, event_num, time_seconds, type, actor_id,
                                           victim_id, actor_team_id, victim_team_id, weapon, site,
                                           x, y, from_x, from_y, after_detonation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
        insertPosition = conn.prepareStatement("""
                INSERT INTO replay_positions (game_id, round_num, event_num, player_id, x, y, facing)
                VALUES (?, ?, ?, ?, ?, ?, ?)""");
        insertState = conn.prepareStatement("""
                INSERT INTO game_states (game_id, round_num, state_num, event_num, time_seconds,
                    attacker_id, defender_id, atk_alive, def_alive, spike_planted, plant_site,
                    seconds_since_plant, atk_loadout, def_loadout, atk_start_loadout, def_start_loadout,
                    atk_bank, def_bank, atk_rifles, def_rifles, atk_snipers, def_snipers,
                    atk_heavy_armor, def_heavy_armor, atk_score, def_score, atk_won)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
    }

    /** Writes one map's game states (see GameStates). Returns how many rows were written. */
    public int insertGameStates(long gameId, ReplayPage replay, List<RoundResult> rounds,
                                long team1Id, long team2Id) throws SQLException {
        List<GameStates.State> states = GameStates.build(replay, rounds);
        for (GameStates.State s : states) {
            long atk = s.atkTeam() == 1 ? team1Id : team2Id;
            long def = s.atkTeam() == 1 ? team2Id : team1Id;
            int i = 1;
            insertState.setLong(i++, gameId);
            insertState.setInt(i++, s.roundNum());
            insertState.setInt(i++, s.stateNum());
            setInt(insertState, i++, s.eventNum());
            insertState.setInt(i++, s.timeSeconds());
            insertState.setLong(i++, atk);
            insertState.setLong(i++, def);
            insertState.setInt(i++, s.atkAlive());
            insertState.setInt(i++, s.defAlive());
            insertState.setInt(i++, s.planted() ? 1 : 0);
            insertState.setString(i++, s.plantSite());
            setInt(insertState, i++, s.secondsSincePlant());
            insertState.setInt(i++, s.atkLoadout());
            insertState.setInt(i++, s.defLoadout());
            insertState.setInt(i++, s.atkStartLoadout());
            insertState.setInt(i++, s.defStartLoadout());
            insertState.setInt(i++, s.atkBank());
            insertState.setInt(i++, s.defBank());
            insertState.setInt(i++, s.atkRifles());
            insertState.setInt(i++, s.defRifles());
            insertState.setInt(i++, s.atkSnipers());
            insertState.setInt(i++, s.defSnipers());
            insertState.setInt(i++, s.atkHeavy());
            insertState.setInt(i++, s.defHeavy());
            insertState.setInt(i++, s.atkScore());
            insertState.setInt(i++, s.defScore());
            insertState.setInt(i, s.atkWon() ? 1 : 0);
            insertState.addBatch();
        }
        insertState.executeBatch();
        return states.size();
    }

    /**
     * Writes a team's name and tag. Call in chronological order so the latest name wins
     * (a team ID keeps its history through rebrands).
     */
    public void putTeam(MatchPage.Team team) throws SQLException {
        upsertTeam.setLong(1, team.id());
        upsertTeam.setString(2, team.name());
        upsertTeam.setString(3, team.tag());
        upsertTeam.executeUpdate();
    }

    /** Same idea as putTeam: chronological order, so the latest handle and country win. */
    public void putPlayer(PlayerStats p) throws SQLException {
        upsertPlayer.setLong(1, p.playerId());
        upsertPlayer.setString(2, p.name());
        upsertPlayer.setString(3, p.country());
        upsertPlayer.executeUpdate();
    }

    /** Inserts the match, its event (once), its maps, player stats, and rounds. Teams and players must exist. */
    public void insertMatch(MatchPage p) throws SQLException {
        EventInfo info = EventInfo.of(p.eventName());
        insertEvent.setLong(1, p.eventId());
        insertEvent.setString(2, p.eventName());
        setInt(insertEvent, 3, info.year());
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
        setInt(insertMatch, 6, p.bestOf() == 0 ? null : p.bestOf());
        insertMatch.setLong(7, t1);
        insertMatch.setLong(8, t2);
        insertMatch.setInt(9, p.team1Score());
        insertMatch.setInt(10, p.team2Score());
        setLong(insertMatch, 11, winner(p.team1Score(), p.team2Score(), t1, t2));
        insertMatch.setString(12, p.veto());
        insertMatch.setInt(13, p.isShowmatch() ? 1 : 0);
        insertMatch.executeUpdate();

        for (MapResult m : p.maps()) {
            insertMap.setLong(1, m.gameId());
            insertMap.setLong(2, p.matchId());
            insertMap.setInt(3, m.mapNumber());
            insertMap.setString(4, m.mapName());
            setLong(insertMap, 5, teamFor(m.pickedBy(), t1, t2));
            insertMap.setInt(6, m.team1Score());
            insertMap.setInt(7, m.team2Score());
            setLong(insertMap, 8, winner(m.team1Score(), m.team2Score(), t1, t2));
            insertMap.setString(9, m.team1StartSide());
            setInt(insertMap, 10, m.team1Atk());
            setInt(insertMap, 11, m.team1Def());
            setInt(insertMap, 12, m.team1Ot());
            setInt(insertMap, 13, m.team2Atk());
            setInt(insertMap, 14, m.team2Def());
            setInt(insertMap, 15, m.team2Ot());
            setInt(insertMap, 16, m.durationSeconds());
            insertMap.executeUpdate();

            for (PlayerStats ps : m.players()) {
                for (SideStats s : ps.sides()) {
                    insertStats.setLong(1, m.gameId());
                    insertStats.setLong(2, ps.playerId());
                    insertStats.setLong(3, ps.team() == 1 ? t1 : t2);
                    insertStats.setString(4, s.side());
                    insertStats.setString(5, ps.agent());
                    if (s.rating() == null) {
                        insertStats.setNull(6, Types.REAL);
                    } else {
                        insertStats.setDouble(6, s.rating());
                    }
                    setInt(insertStats, 7, s.acs());
                    setInt(insertStats, 8, s.kills());
                    setInt(insertStats, 9, s.deaths());
                    setInt(insertStats, 10, s.assists());
                    setInt(insertStats, 11, s.kastPct());
                    setInt(insertStats, 12, s.adr());
                    setInt(insertStats, 13, s.hsPct());
                    setInt(insertStats, 14, s.firstKills());
                    setInt(insertStats, 15, s.firstDeaths());
                    insertStats.addBatch();
                }
            }
            insertStats.executeBatch();

            for (RoundResult r : m.rounds()) {
                long winnerId = r.winner() == 1 ? t1 : t2;
                long loserId = r.winner() == 1 ? t2 : t1;
                insertRound.setLong(1, m.gameId());
                insertRound.setInt(2, r.number());
                insertRound.setLong(3, winnerId);
                insertRound.setString(4, r.winnerSide());
                setLong(insertRound, 5, "atk".equals(r.winnerSide()) ? Long.valueOf(winnerId)
                        : "def".equals(r.winnerSide()) ? Long.valueOf(loserId) : null);
                insertRound.setString(6, r.outcome());
                insertRound.setInt(7, r.team1Score());
                insertRound.setInt(8, r.team2Score());
                insertRound.addBatch();
            }
            insertRound.executeBatch();
        }
    }

    /**
     * Inserts one map's replay. Replay player IDs are translated to vlr.gg IDs by name
     * ({@code playerByName}, from that map's stat table). Returns names that couldn't be
     * matched; their rows are skipped rather than guessed.
     */
    public List<String> insertReplay(long gameId, ReplayPage replay, Map<String, Long> playerByName,
                                     long team1Id, long team2Id) throws SQLException {
        List<String> unmatched = new ArrayList<>();
        if (replay.rounds().isEmpty()) {
            return unmatched;
        }
        markReplay.setLong(1, gameId);
        markReplay.executeUpdate();

        for (ReplayPage.Round r : replay.rounds()) {
            Map<Long, Long> idMap = new HashMap<>();
            Map<Long, Integer> teamOf = new HashMap<>();
            for (ReplayPage.Player p : r.players()) {
                Long playerId = playerByName.get(p.name());
                if (playerId == null) {
                    unmatched.add(p.name());
                    continue;
                }
                idMap.put(p.replayId(), playerId);
                teamOf.put(playerId, p.team());
            }

            for (Loadout l : r.loadouts()) {
                Long playerId = playerByName.get(l.name());
                if (playerId == null) {
                    unmatched.add(l.name());
                    continue;
                }
                Integer team = teamOf.get(playerId);
                insertEconomy.setLong(1, gameId);
                insertEconomy.setInt(2, r.number());
                insertEconomy.setLong(3, playerId);
                insertEconomy.setLong(4, team != null && team == 2 ? team2Id : team1Id);
                insertEconomy.setString(5, l.side());
                insertEconomy.setString(6, l.agent());
                setInt(insertEconomy, 7, l.creditsSpent());
                setInt(insertEconomy, 8, l.creditsLeft());
                insertEconomy.setString(9, l.armor());
                insertEconomy.setString(10, l.weapon());
                insertEconomy.addBatch();
            }
            insertEconomy.executeBatch();

            for (Event e : r.events()) {
                // Teams come from the roster: the JSON "team" field is the victim's team on kills
                // but the actor's on plants and defuses.
                Long actor = e.actorId() == null ? null : idMap.get(e.actorId());
                Long victim = e.victimId() == null ? null : idMap.get(e.victimId());
                insertReplayEvent.setLong(1, gameId);
                insertReplayEvent.setInt(2, r.number());
                insertReplayEvent.setInt(3, e.index());
                setInt(insertReplayEvent, 4, e.timeSeconds());
                insertReplayEvent.setString(5, e.type());
                setLong(insertReplayEvent, 6, actor);
                setLong(insertReplayEvent, 7, victim);
                setLong(insertReplayEvent, 8, actor == null ? null : teamFor(teamOf.get(actor), team1Id, team2Id));
                setLong(insertReplayEvent, 9, victim == null ? null : teamFor(teamOf.get(victim), team1Id, team2Id));
                insertReplayEvent.setString(10, e.weapon());
                insertReplayEvent.setString(11, e.site());
                setDouble(insertReplayEvent, 12, e.x());
                setDouble(insertReplayEvent, 13, e.y());
                setDouble(insertReplayEvent, 14, e.fromX());
                setDouble(insertReplayEvent, 15, e.fromY());
                insertReplayEvent.setInt(16, e.afterDetonation() ? 1 : 0);
                insertReplayEvent.addBatch();
            }
            insertReplayEvent.executeBatch();

            for (Event e : r.events()) {
                for (Position pos : e.positions()) {
                    Long playerId = idMap.get(pos.replayId());
                    if (playerId == null) {
                        continue; // already reported via the roster
                    }
                    insertPosition.setLong(1, gameId);
                    insertPosition.setInt(2, r.number());
                    insertPosition.setInt(3, e.index());
                    insertPosition.setLong(4, playerId);
                    insertPosition.setDouble(5, pos.x());
                    insertPosition.setDouble(6, pos.y());
                    setInt(insertPosition, 7, pos.facing());
                    insertPosition.addBatch();
                }
            }
            insertPosition.executeBatch();
        }
        return unmatched;
    }

    private static Long winner(int score1, int score2, long t1, long t2) {
        return teamFor(score1 > score2 ? 1 : score2 > score1 ? 2 : 0, t1, t2);
    }

    /** 1 or 2 to that team's ID, anything else to null. Boxed on purpose: see the decider maps. */
    private static Long teamFor(Integer which, long t1, long t2) {
        if (which != null && which == 1) {
            return t1;
        }
        if (which != null && which == 2) {
            return t2;
        }
        return null;
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.REAL);
        } else {
            ps.setDouble(i, v);
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
