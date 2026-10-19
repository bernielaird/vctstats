package dev.bernie.vct.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildDbTest {

    @TempDir
    Path dir;

    @Test
    void validMapScores() {
        assertTrue(BuildDb.isValidMapScore(13, 11));
        assertTrue(BuildDb.isValidMapScore(1, 13));
        assertTrue(BuildDb.isValidMapScore(14, 12));
        assertTrue(BuildDb.isValidMapScore(16, 18));
        assertFalse(BuildDb.isValidMapScore(13, 12)); // 12-12 goes to overtime
        assertFalse(BuildDb.isValidMapScore(14, 11));
        assertFalse(BuildDb.isValidMapScore(12, 10));
    }

    @Test
    void buildsDatabaseFromSavedPages() throws Exception {
        Path matches = Files.createDirectories(dir.resolve("matches"));
        copyFixture("match-734308.html", matches.resolve("734308.html")); // VCT grand final
        copyFixture("match-744488.html", matches.resolve("744488.html")); // Game Changers: filtered out
        Path logs = Files.createDirectories(dir.resolve("logs"));
        copyFixture("logs-734308-280042.html", logs.resolve("734308-280042.html")); // Haven replay only
        Path db = dir.resolve("vct.db");

        new BuildDb().run(matches, logs, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, single(st, "SELECT COUNT(*) FROM matches"));
            assertEquals(5, single(st, "SELECT COUNT(*) FROM maps"));
            assertEquals(120, single(st, "SELECT winner_id FROM matches WHERE match_id = 734308"));
            assertEquals(3, single(st, "SELECT COUNT(*) FROM maps WHERE winner_id = 120"));
            // Decider has no picker; Split was 100T's pick and LOUD won it.
            assertEquals(1, single(st, "SELECT COUNT(*) FROM maps WHERE picked_by_id IS NULL"));
            assertEquals(6961, single(st, "SELECT winner_id FROM maps WHERE map_name = 'Split'"));
            // The view gives one row per team: 100T won, LOUD lost.
            assertEquals(1, single(st, "SELECT won FROM team_matches WHERE team_id = 120"));
            assertEquals(0, single(st, "SELECT won FROM team_matches WHERE team_id = 6961"));
            try (ResultSet rs = st.executeQuery("SELECT type, region FROM events WHERE event_id = 2977")) {
                assertTrue(rs.next());
                assertEquals("league", rs.getString(1));
                assertEquals("Americas", rs.getString(2));
            }

            // Players and per-side stats: 10 players x 5 maps x 3 sides.
            assertEquals(10, single(st, "SELECT COUNT(*) FROM players"));
            assertEquals(150, single(st, "SELECT COUNT(*) FROM player_map_stats"));
            assertEquals(27, single(st, "SELECT kills FROM player_map_stats WHERE game_id = 280042 "
                    + "AND player_id = 29893 AND side = 'both'"));
            assertEquals(110, single(st, "SELECT COUNT(*) FROM rounds")); // 20 + 24 + 26 + 14 + 26
            assertEquals("100T", text(st, "SELECT tag FROM teams WHERE team_id = 120"));
            // Round 3 on Haven: LOUD attacking, spike detonated.
            assertEquals(6961, single(st, "SELECT attacker_id FROM rounds WHERE game_id = 280042 AND round_num = 3"));

            // Only Haven has a replay file here.
            assertEquals(1, single(st, "SELECT COUNT(*) FROM maps WHERE has_replay = 1"));
            assertEquals(260, single(st, "SELECT COUNT(*) FROM round_economy")); // 26 rounds x 10
            assertEquals(0, single(st, "SELECT COUNT(*) FROM replay_events WHERE type = 'kill' AND actor_id IS NULL "
                    + "AND weapon NOT IN ('Spike', 'Fall')"));
            // 179 deaths in the replay: 177 kills (matches the stat table) + 2 spike deaths with no killer.
            assertEquals(177, single(st, "SELECT SUM(kills) FROM player_map_stats WHERE game_id = 280042 AND side = 'both'"));
            assertEquals(177, single(st, "SELECT COUNT(*) FROM replay_events WHERE game_id = 280042 "
                    + "AND type = 'kill' AND actor_id IS NOT NULL"));
            assertEquals(2, single(st, "SELECT COUNT(*) FROM replay_events WHERE game_id = 280042 "
                    + "AND type = 'kill' AND actor_id IS NULL AND weapon = 'Spike'"));
            assertEquals(20140, single(st, "SELECT actor_id FROM replay_events WHERE game_id = 280042 "
                    + "AND round_num = 1 AND event_num = 0")); // vora
            assertEquals(120, single(st, "SELECT actor_team_id FROM replay_events WHERE game_id = 280042 "
                    + "AND round_num = 1 AND event_num = 0"));  // vora plays for 100T...
            assertEquals(6961, single(st, "SELECT victim_team_id FROM replay_events WHERE game_id = 280042 "
                    + "AND round_num = 1 AND event_num = 0"));  // ...and killed tkzin of LOUD
            assertEquals(0, single(st, "SELECT COUNT(*) FROM replay_events WHERE game_id = 280042 "
                    + "AND actor_team_id = victim_team_id"));    // no teamkills on this map
            assertTrue(single(st, "SELECT COUNT(*) FROM replay_positions") > 1000);

            // Game states. Round 1 on Haven: LOUD attacking, 100T won.
            assertEquals(26, single(st, "SELECT COUNT(*) FROM game_states WHERE game_id = 280042 AND state_num = 0"));
            String r1 = "FROM game_states WHERE game_id = 280042 AND round_num = 1 ";
            assertEquals(6961, single(st, "SELECT attacker_id " + r1 + "AND state_num = 0"));
            assertEquals(0, single(st, "SELECT atk_won " + r1 + "AND state_num = 0"));
            assertEquals(55, single(st, "SELECT atk_alive * 10 + def_alive " + r1 + "AND state_num = 0"));
            assertEquals(0, single(st, "SELECT time_seconds " + r1 + "AND state_num = 0"));
            // vora (100T, defending) kills tkzin at 0:14: attackers drop to 4.
            assertEquals(45, single(st, "SELECT atk_alive * 10 + def_alive " + r1 + "AND state_num = 1"));
            assertEquals(14, single(st, "SELECT time_seconds " + r1 + "AND state_num = 1"));
            assertEquals(0, single(st, "SELECT event_num " + r1 + "AND state_num = 1"));
            // tkzin's gear leaves with him; pistol-round loadouts are small.
            assertTrue(single(st, "SELECT atk_loadout " + r1 + "AND state_num = 1")
                    < single(st, "SELECT atk_loadout " + r1 + "AND state_num = 0"));
            assertTrue(single(st, "SELECT atk_start_loadout " + r1 + "AND state_num = 0") <= 5 * 1000);
            // Never a state where the round is already decided.
            assertEquals(0, single(st, "SELECT COUNT(*) FROM game_states WHERE atk_alive = 0 OR def_alive = 0"));
            // Post-plant states know the site and the spike clock.
            assertEquals(0, single(st, "SELECT COUNT(*) FROM game_states WHERE spike_planted = 1 "
                    + "AND (plant_site IS NULL OR seconds_since_plant IS NULL OR seconds_since_plant < 0)"));
            assertTrue(single(st, "SELECT COUNT(*) FROM game_states WHERE spike_planted = 1") > 20);
            assertEquals(1, single(st, "SELECT COUNT(*) FROM player_maps_full WHERE game_id = 280042 AND player = 'Timotino'"));
        }
        assertFalse(Files.exists(dir.resolve("vct.db.building")));
    }

    private void copyFixture(String name, Path to) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            Files.copy(in, to);
        }
    }

    private static String text(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private static long single(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getLong(1);
        }
    }
}
