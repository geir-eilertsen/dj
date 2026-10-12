package dj.adapter.sqlite;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.port.ArtistProfileStore;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Artist profiles in a local SQLite database, mirrored in memory.
 *
 * <p>Every mix reads profiles, so reads are served from memory; writes (from the background
 * enrichment) go to both.
 */
public class SqliteArtistProfileStore implements ArtistProfileStore {

    private static final int SCHEMA_VERSION = 1;

    private final String url;
    private final Map<String, ArtistProfile> cache = new ConcurrentHashMap<>();

    public SqliteArtistProfileStore(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.url = "jdbc:sqlite:" + dbFile;
            try (Connection db = connect()) {
                migrate(db);
                loadAll(db);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not open artist database " + dbFile, e);
        }
    }

    @Override
    public Optional<ArtistProfile> find(String artistName) {
        return Optional.ofNullable(cache.get(ArtistNames.key(artistName)));
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public synchronized void save(ArtistProfile profile) {
        String key = ArtistNames.key(profile.name());
        try (Connection db = connect()) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = db.prepareStatement(
                        "INSERT INTO artist (key, name, fetched_at) VALUES (?, ?, ?) "
                                + "ON CONFLICT (key) DO UPDATE SET name = excluded.name, fetched_at = excluded.fetched_at")) {
                    upsert.setString(1, key);
                    upsert.setString(2, profile.name());
                    upsert.setLong(3, profile.fetchedAt().toEpochMilli());
                    upsert.executeUpdate();
                }
                for (String table : List.of("artist_tag", "similar_artist")) {
                    try (PreparedStatement delete = db.prepareStatement("DELETE FROM " + table + " WHERE artist_key = ?")) {
                        delete.setString(1, key);
                        delete.executeUpdate();
                    }
                }
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO artist_tag (artist_key, position, tag, weight) VALUES (?, ?, ?, ?)")) {
                    int position = 0;
                    for (ArtistProfile.Tag tag : profile.tags()) {
                        insert.setString(1, key);
                        insert.setInt(2, position++);
                        insert.setString(3, tag.name());
                        insert.setDouble(4, tag.weight());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO similar_artist (artist_key, position, name, match) VALUES (?, ?, ?, ?)")) {
                    int position = 0;
                    for (ArtistProfile.SimilarArtist similar : profile.similar()) {
                        insert.setString(1, key);
                        insert.setInt(2, position++);
                        insert.setString(3, similar.name());
                        insert.setDouble(4, similar.match());
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
            throw new IllegalStateException("Could not save artist " + profile.name(), e);
        }
        cache.put(key, profile);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
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
                    CREATE TABLE IF NOT EXISTS artist (
                        key        TEXT PRIMARY KEY,
                        name       TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS artist_tag (
                        artist_key TEXT NOT NULL REFERENCES artist (key),
                        position   INTEGER NOT NULL,
                        tag        TEXT NOT NULL,
                        weight     REAL NOT NULL,
                        PRIMARY KEY (artist_key, position)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS similar_artist (
                        artist_key TEXT NOT NULL REFERENCES artist (key),
                        position   INTEGER NOT NULL,
                        name       TEXT NOT NULL,
                        match      REAL NOT NULL,
                        PRIMARY KEY (artist_key, position)
                    )""");
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private void loadAll(Connection db) throws SQLException {
        Map<String, List<ArtistProfile.Tag>> tags = new HashMap<>();
        Map<String, List<ArtistProfile.SimilarArtist>> similar = new HashMap<>();
        try (Statement st = db.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT artist_key, tag, weight FROM artist_tag ORDER BY artist_key, position")) {
                while (rs.next()) {
                    tags.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new ArtistProfile.Tag(rs.getString(2), rs.getDouble(3)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT artist_key, name, match FROM similar_artist ORDER BY artist_key, position")) {
                while (rs.next()) {
                    similar.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new ArtistProfile.SimilarArtist(rs.getString(2), rs.getDouble(3)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT key, name, fetched_at FROM artist")) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    cache.put(key, new ArtistProfile(rs.getString(2),
                            tags.getOrDefault(key, List.of()),
                            similar.getOrDefault(key, List.of()),
                            Instant.ofEpochMilli(rs.getLong(3))));
                }
            }
        }
    }
}
