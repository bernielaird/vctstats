package dev.bernie.vct.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed to-do list for the scrape.
 *
 * Pass 1 (discover) inserts every match it finds as 'pending'.
 * Pass 2 (fetch) works through pending rows and marks them 'done' or 'failed'.
 * Because progress lives here, you can stop and restart the scraper at any time.
 */
public class MatchQueue implements AutoCloseable {

    public record QueuedMatch(long matchId, String url, int attempts) {}

    private final Connection conn;

    public MatchQueue(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS match_queue (
                    match_id   INTEGER PRIMARY KEY,
                    url        TEXT    NOT NULL,
                    status     TEXT    NOT NULL DEFAULT 'pending',
                    attempts   INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    found_at   TEXT    NOT NULL DEFAULT (datetime('now')),
                    fetched_at TEXT
                )""");
        }
    }

    /** Adds a match if we haven't seen it. Returns true if it was new. */
    public boolean enqueue(long matchId, String url) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO match_queue (match_id, url) VALUES (?, ?)")) {
            ps.setLong(1, matchId);
            ps.setString(2, url);
            return ps.executeUpdate() == 1;
        }
    }

    public List<QueuedMatch> nextPending(int limit, int maxAttempts) throws SQLException {
        List<QueuedMatch> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT match_id, url, attempts FROM match_queue
                WHERE status = 'pending' AND attempts < ?
                ORDER BY match_id
                LIMIT ?""")) {
            ps.setInt(1, maxAttempts);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new QueuedMatch(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                }
            }
        }
        return out;
    }

    public boolean contains(long matchId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM match_queue WHERE match_id = ?")) {
            ps.setLong(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean isEmpty() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM match_queue LIMIT 1")) {
            return !rs.next();
        }
    }

    /** Every match whose overview page has been downloaded. */
    public List<Long> doneMatchIds() throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT match_id FROM match_queue WHERE status = 'done' ORDER BY match_id")) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    public void markDone(long matchId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE match_queue
                SET status = 'done', fetched_at = datetime('now'), last_error = NULL
                WHERE match_id = ?""")) {
            ps.setLong(1, matchId);
            ps.executeUpdate();
        }
    }

    /** Records a failure; after maxAttempts the row is parked as 'failed'. */
    public void markAttemptFailed(long matchId, String error, int maxAttempts) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE match_queue
                SET attempts = attempts + 1,
                    last_error = ?,
                    status = CASE WHEN attempts + 1 >= ? THEN 'failed' ELSE 'pending' END
                WHERE match_id = ?""")) {
            ps.setString(1, error);
            ps.setInt(2, maxAttempts);
            ps.setLong(3, matchId);
            ps.executeUpdate();
        }
    }

    /** Puts failed rows back in the queue so `fetch` will try them again. */
    public int resetFailed() throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(
                    "UPDATE match_queue SET status = 'pending', attempts = 0 WHERE status = 'failed'");
        }
    }

    public Map<String, Integer> statusCounts() throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status, COUNT(*) FROM match_queue GROUP BY status ORDER BY status")) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
