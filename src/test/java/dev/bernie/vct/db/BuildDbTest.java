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
        Path db = dir.resolve("vct.db");

        new BuildDb().run(matches, db);

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
        }
        assertFalse(Files.exists(dir.resolve("vct.db.building")));
    }

    private void copyFixture(String name, Path to) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            Files.copy(in, to);
        }
    }

    private static long single(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getLong(1);
        }
    }
}
