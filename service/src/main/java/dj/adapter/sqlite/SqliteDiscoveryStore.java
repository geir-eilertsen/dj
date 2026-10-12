package dj.adapter.sqlite;

import dj.domain.model.ArtistNames;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.DiscoveryStore;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovered tracks and listened artists in a local SQLite database, mirrored in memory.
 */
public class SqliteDiscoveryStore implements DiscoveryStore {

    private static final int SCHEMA_VERSION = 1;

    private final String url;
    /** Artist key → tracks, in insertion order. */
    private final Map<String, List<Track>> tracksByArtist = new LinkedHashMap<>();
    private final Map<String, Instant> discoveredAt = new ConcurrentHashMap<>();
    private final Map<String, String> listened = new ConcurrentHashMap<>();
    private volatile List<Track> allTracks = List.of();

    public SqliteDiscoveryStore(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.url = "jdbc:sqlite:" + dbFile;
            try (Connection db = DriverManager.getConnection(url)) {
                migrate(db);
                loadAll(db);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not open discovery database " + dbFile, e);
        }
    }

    @Override
    public List<Track> allTracks() {
        return allTracks;
    }

    @Override
    public Optional<Instant> discoveredAt(String artist) {
        return Optional.ofNullable(discoveredAt.get(ArtistNames.key(artist)));
    }

    @Override
    public List<String> listenedArtists() {
        return List.copyOf(listened.values());
    }

    @Override
    public synchronized void save(String artist, List<Track> tracks, Instant at) {
        String key = ArtistNames.key(artist);
        try (Connection db = DriverManager.getConnection(url)) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = db.prepareStatement(
                        "INSERT INTO discovered_artist (key, name, discovered_at) VALUES (?, ?, ?) "
                                + "ON CONFLICT (key) DO UPDATE SET name = excluded.name, discovered_at = excluded.discovered_at")) {
                    upsert.setString(1, key);
                    upsert.setString(2, artist);
                    upsert.setLong(3, at.toEpochMilli());
                    upsert.executeUpdate();
                }
                try (PreparedStatement delete = db.prepareStatement("DELETE FROM discovered_track WHERE artist_key = ?")) {
                    delete.setString(1, key);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO discovered_track (artist_key, position, url, title, artist, album) VALUES (?, ?, ?, ?, ?, ?)")) {
                    int position = 0;
                    for (Track track : tracks) {
                        insert.setString(1, key);
                        insert.setInt(2, position++);
                        insert.setString(3, track.url());
                        insert.setString(4, track.title());
                        insert.setString(5, track.artist());
                        insert.setString(6, track.album());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                db.commit();
            } catch (SQLException e) {
                db.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save discovered tracks for " + artist, e);
        }
        discoveredAt.put(key, at);
        tracksByArtist.put(key, List.copyOf(tracks));
        allTracks = tracksByArtist.values().stream().flatMap(List::stream).toList();
    }

    @Override
    public synchronized void rememberListened(String artist, Instant heardAt) {
        String key = ArtistNames.key(artist);
        try (Connection db = DriverManager.getConnection(url);
             PreparedStatement upsert = db.prepareStatement(
                     "INSERT INTO listened_artist (key, name, last_heard) VALUES (?, ?, ?) "
                             + "ON CONFLICT (key) DO UPDATE SET name = excluded.name, last_heard = excluded.last_heard")) {
            upsert.setString(1, key);
            upsert.setString(2, artist);
            upsert.setLong(3, heardAt.toEpochMilli());
            upsert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not remember listened artist " + artist, e);
        }
        listened.put(key, artist);
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
                    CREATE TABLE IF NOT EXISTS discovered_artist (
                        key           TEXT PRIMARY KEY,
                        name          TEXT NOT NULL,
                        discovered_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS discovered_track (
                        artist_key TEXT NOT NULL REFERENCES discovered_artist (key),
                        position   INTEGER NOT NULL,
                        url        TEXT NOT NULL,
                        title      TEXT NOT NULL,
                        artist     TEXT NOT NULL,
                        album      TEXT NOT NULL,
                        PRIMARY KEY (artist_key, position)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS listened_artist (
                        key        TEXT PRIMARY KEY,
                        name       TEXT NOT NULL,
                        last_heard INTEGER NOT NULL
                    )""");
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private void loadAll(Connection db) throws SQLException {
        try (Statement st = db.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT key, discovered_at FROM discovered_artist")) {
                while (rs.next()) {
                    discoveredAt.put(rs.getString(1), Instant.ofEpochMilli(rs.getLong(2)));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT artist_key, url, title, artist, album FROM discovered_track ORDER BY artist_key, position")) {
                while (rs.next()) {
                    String url = rs.getString(2);
                    tracksByArtist.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new Track(TrackId.of(url), url, rs.getString(3), rs.getString(4), rs.getString(5), 0, 0));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT key, name FROM listened_artist")) {
                while (rs.next()) {
                    listened.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        tracksByArtist.replaceAll((k, v) -> List.copyOf(v));
        allTracks = tracksByArtist.values().stream().flatMap(List::stream).toList();
    }
}
