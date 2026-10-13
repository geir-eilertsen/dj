package dj.adapter.sqlite;

import dj.domain.model.Mood;
import dj.domain.port.MoodStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Moods in a local SQLite database, one row per time a mood was set. Tags and artists are
 * stored one per line. Read once per mix; small enough not to cache.
 */
public class SqliteMoodStore implements MoodStore {

    private static final int SCHEMA_VERSION = 1;
    private static final String COLUMNS = "text, tags, artists, from_year, to_year, set_at";
    /** Rows scanned for recent moods; repeats of the same text are skipped. */
    private static final int RECENT_SCAN = 100;

    private final String url;

    public SqliteMoodStore(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.url = "jdbc:sqlite:" + dbFile;
            try (Connection db = DriverManager.getConnection(url)) {
                migrate(db);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not open mood store " + dbFile, e);
        }
    }

    @Override
    public synchronized void save(String playerId, Mood mood) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement insert = db.prepareStatement(
                     "INSERT INTO mood (player, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, playerId);
            insert.setString(2, mood.text());
            insert.setString(3, lines(mood.style().tags()));
            insert.setString(4, lines(mood.style().artists()));
            insert.setInt(5, mood.style().fromYear());
            insert.setInt(6, mood.style().toYear());
            insert.setLong(7, mood.setAt().toEpochMilli());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save mood", e);
        }
    }

    @Override
    public Optional<Mood> latest(String playerId) {
        return select("SELECT " + COLUMNS + " FROM mood WHERE player = ? AND cleared_at IS NULL "
                + "ORDER BY set_at DESC LIMIT 1", playerId).stream().findFirst();
    }

    @Override
    public synchronized void clear(String playerId, Instant at) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement update = db.prepareStatement(
                     "UPDATE mood SET cleared_at = ? WHERE player = ? AND cleared_at IS NULL")) {
            update.setLong(1, at.toEpochMilli());
            update.setString(2, playerId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear mood", e);
        }
    }

    @Override
    public List<Mood> recent(String playerId, int limit) {
        List<Mood> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Mood mood : select("SELECT " + COLUMNS + " FROM mood WHERE player = ? ORDER BY set_at DESC LIMIT "
                + RECENT_SCAN, playerId)) {
            if (recent.size() < limit && seen.add(mood.text().toLowerCase(Locale.ROOT))) {
                recent.add(mood);
            }
        }
        return recent;
    }

    private List<Mood> select(String sql, String playerId) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement select = db.prepareStatement(sql)) {
            select.setString(1, playerId);
            List<Mood> moods = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    moods.add(new Mood(rs.getString(1),
                            new Mood.Style(split(rs.getString(2)), split(rs.getString(3)), rs.getInt(4), rs.getInt(5)),
                            Instant.ofEpochMilli(rs.getLong(6))));
                }
            }
            return moods;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read moods", e);
        }
    }

    private static String lines(List<String> values) {
        return String.join("\n", values.stream().map(v -> v.replace('\n', ' ')).toList());
    }

    private static List<String> split(String lines) {
        return lines == null || lines.isEmpty() ? List.of() : Arrays.asList(lines.split("\n"));
    }

    private static void migrate(Connection db) throws SQLException {
        try (Statement st = db.createStatement()) {
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version < 1) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS mood (
                            player     TEXT NOT NULL,
                            text       TEXT NOT NULL,
                            tags       TEXT NOT NULL,
                            artists    TEXT NOT NULL,
                            from_year  INTEGER NOT NULL,
                            to_year    INTEGER NOT NULL,
                            set_at     INTEGER NOT NULL,
                            cleared_at INTEGER
                        )""");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS mood_by_player_time ON mood (player, set_at)");
            }
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }
}
