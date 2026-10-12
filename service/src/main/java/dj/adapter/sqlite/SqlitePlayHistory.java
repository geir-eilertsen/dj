package dj.adapter.sqlite;

import dj.domain.model.Play;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
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
import java.util.Optional;

/** The listening log in a local SQLite database. Queried rarely (Start DJ), so not cached. */
public class SqlitePlayHistory implements PlayHistory {

    private static final int SCHEMA_VERSION = 1;
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
            if (version >= SCHEMA_VERSION) {
                return;
            }
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
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }
}
