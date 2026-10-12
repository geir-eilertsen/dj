package dj.adapter.sqlite;

import dj.domain.model.Play;
import dj.domain.model.Skip;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.FeedbackStore;
import dj.domain.port.PlayHistory;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The listening log, suggestions and skips in a local SQLite database. Plays are queried
 * rarely (Start DJ), so not cached; skips are read once per mix and stay a small table.
 */
public class SqlitePlayHistory implements PlayHistory, FeedbackStore {

    private static final int SCHEMA_VERSION = 3;
    private static final String COLUMNS = "player, started_at, track_id, url, title, artist, album, year";

    private final String url;

    public SqlitePlayHistory(Path dbFile) {
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
            throw new IllegalStateException("Could not open play history " + dbFile, e);
        }
    }

    @Override
    public synchronized void record(Play play) {
        Track t = play.track();
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement insert = db.prepareStatement(
                     "INSERT INTO play (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, play.playerId());
            insert.setLong(2, play.startedAt().toEpochMilli());
            insert.setString(3, t.id().value());
            insert.setString(4, t.url());
            insert.setString(5, t.title());
            insert.setString(6, t.artist());
            insert.setString(7, t.album());
            insert.setInt(8, t.year());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record play of " + t.title(), e);
        }
    }

    @Override
    public List<Play> playsOn(String playerId, Instant since) {
        return query("SELECT " + COLUMNS + " FROM play WHERE player = ? AND started_at >= ? ORDER BY started_at",
                playerId, since.toEpochMilli());
    }

    @Override
    public Optional<Play> lastPlayOn(String playerId) {
        return query("SELECT " + COLUMNS + " FROM play WHERE player = ? AND started_at >= ? ORDER BY started_at DESC LIMIT 1",
                playerId, 0L).stream().findFirst();
    }

    @Override
    public synchronized void recordSuggested(String playerId, List<Track> tracks, Map<String, String> reasons, Instant at) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement insert = db.prepareStatement(
                     "INSERT INTO suggestion (player, url, suggested_at, reason) VALUES (?, ?, ?, ?)")) {
            for (Track track : tracks) {
                insert.setString(1, playerId);
                insert.setString(2, track.url());
                insert.setLong(3, at.toEpochMilli());
                insert.setString(4, reasons.get(track.url()));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record suggestions", e);
        }
    }

    @Override
    public Optional<String> reasonFor(String playerId, String trackUrl) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement select = db.prepareStatement(
                     "SELECT reason FROM suggestion WHERE player = ? AND url = ? ORDER BY suggested_at DESC LIMIT 1")) {
            select.setString(1, playerId);
            select.setString(2, trackUrl);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read suggestions", e);
        }
    }

    @Override
    public boolean wasSuggested(String playerId, String trackUrl, Instant since) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement select = db.prepareStatement(
                     "SELECT 1 FROM suggestion WHERE player = ? AND url = ? AND suggested_at >= ? LIMIT 1")) {
            select.setString(1, playerId);
            select.setString(2, trackUrl);
            select.setLong(3, since.toEpochMilli());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read suggestions", e);
        }
    }

    @Override
    public synchronized void recordSkip(Skip skip) {
        Track t = skip.track();
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement insert = db.prepareStatement(
                     "INSERT INTO skip (player, skipped_at, url, title, artist) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, skip.playerId());
            insert.setLong(2, skip.at().toEpochMilli());
            insert.setString(3, t.url());
            insert.setString(4, t.title());
            insert.setString(5, t.artist());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record skip of " + t.title(), e);
        }
    }

    @Override
    public List<Skip> skipsSince(Instant since) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement select = db.prepareStatement(
                     "SELECT player, skipped_at, url, title, artist FROM skip WHERE skipped_at >= ? ORDER BY skipped_at")) {
            select.setLong(1, since.toEpochMilli());
            List<Skip> skips = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String trackUrl = rs.getString(3);
                    skips.add(new Skip(rs.getString(1), Instant.ofEpochMilli(rs.getLong(2)),
                            new Track(TrackId.of(trackUrl), trackUrl, rs.getString(4), rs.getString(5), "", 0, 0)));
                }
            }
            return skips;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read skips", e);
        }
    }

    private List<Play> query(String sql, String playerId, long since) {
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement select = db.prepareStatement(sql)) {
            select.setString(1, playerId);
            select.setLong(2, since);
            List<Play> plays = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    plays.add(new Play(rs.getString(1), Instant.ofEpochMilli(rs.getLong(2)),
                            new Track(TrackId.of(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getString(6),
                                    rs.getString(7), rs.getInt(8), 0)));
                }
            }
            return plays;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read play history", e);
        }
    }

    private static void migrate(Connection db) throws SQLException {
        try (Statement st = db.createStatement()) {
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version < 1) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS play (
                            player     TEXT NOT NULL,
                            started_at INTEGER NOT NULL,
                            track_id   TEXT NOT NULL,
                            url        TEXT NOT NULL,
                            title      TEXT NOT NULL,
                            artist     TEXT NOT NULL,
                            album      TEXT NOT NULL,
                            year       INTEGER NOT NULL
                        )""");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS play_by_player_time ON play (player, started_at)");
            }
            if (version < 2) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS suggestion (
                            player       TEXT NOT NULL,
                            url          TEXT NOT NULL,
                            suggested_at INTEGER NOT NULL
                        )""");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS suggestion_by_player_url ON suggestion (player, url)");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS skip (
                            player     TEXT NOT NULL,
                            skipped_at INTEGER NOT NULL,
                            url        TEXT NOT NULL,
                            title      TEXT NOT NULL,
                            artist     TEXT NOT NULL
                        )""");
            }
            if (version < 3) {
                st.executeUpdate("ALTER TABLE suggestion ADD COLUMN reason TEXT");
            }
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }
}
