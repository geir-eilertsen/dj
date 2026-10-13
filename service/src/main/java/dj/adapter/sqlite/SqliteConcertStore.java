package dj.adapter.sqlite;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.port.ConcertStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Concert lookups in a local SQLite database, mirrored in memory. */
public class SqliteConcertStore implements ConcertStore {

    private static final int SCHEMA_VERSION = 1;

    private final String url;
    private final Map<String, ArtistConcerts> byArtist = new ConcurrentHashMap<>();

    public SqliteConcertStore(Path dbFile) {
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
            throw new IllegalStateException("Could not open concert database " + dbFile, e);
        }
    }

    @Override
    public Optional<ArtistConcerts> get(String artist) {
        return Optional.ofNullable(byArtist.get(ArtistNames.key(artist)));
    }

    @Override
    public List<ArtistConcerts> all() {
        return List.copyOf(byArtist.values());
    }

    @Override
    public synchronized void save(ArtistConcerts concerts) {
        String key = ArtistNames.key(concerts.artist());
        try (Connection db = DriverManager.getConnection(url)) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = db.prepareStatement(
                        "INSERT INTO concert_artist (key, name, checked_at) VALUES (?, ?, ?) "
                                + "ON CONFLICT (key) DO UPDATE SET name = excluded.name, checked_at = excluded.checked_at")) {
                    upsert.setString(1, key);
                    upsert.setString(2, concerts.artist());
                    upsert.setLong(3, concerts.checkedAt().toEpochMilli());
                    upsert.executeUpdate();
                }
                try (PreparedStatement delete = db.prepareStatement("DELETE FROM concert WHERE artist_key = ?")) {
                    delete.setString(1, key);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO concert (artist_key, position, title, date, time, venue, city, country, url) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int position = 0;
                    for (Concert c : concerts.concerts()) {
                        insert.setString(1, key);
                        insert.setInt(2, position++);
                        insert.setString(3, c.title());
                        insert.setString(4, c.date().toString());
                        if (c.time() == null) {
                            insert.setNull(5, Types.VARCHAR);
                        } else {
                            insert.setString(5, c.time().toString());
                        }
                        insert.setString(6, c.venue());
                        insert.setString(7, c.city());
                        insert.setString(8, c.countryCode());
                        insert.setString(9, c.url());
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
            throw new IllegalStateException("Could not save concerts for " + concerts.artist(), e);
        }
        byArtist.put(key, concerts);
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
                    CREATE TABLE IF NOT EXISTS concert_artist (
                        key        TEXT PRIMARY KEY,
                        name       TEXT NOT NULL,
                        checked_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS concert (
                        artist_key TEXT NOT NULL REFERENCES concert_artist (key),
                        position   INTEGER NOT NULL,
                        title      TEXT NOT NULL,
                        date       TEXT NOT NULL,
                        time       TEXT,
                        venue      TEXT NOT NULL,
                        city       TEXT NOT NULL,
                        country    TEXT NOT NULL,
                        url        TEXT NOT NULL,
                        PRIMARY KEY (artist_key, position)
                    )""");
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private void loadAll(Connection db) throws SQLException {
        Map<String, List<Concert>> concerts = new HashMap<>();
        try (Statement st = db.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT a.key, a.name, c.title, c.date, c.time, c.venue, c.city, c.country, c.url "
                            + "FROM concert c JOIN concert_artist a ON a.key = c.artist_key ORDER BY c.artist_key, c.position")) {
                while (rs.next()) {
                    String time = rs.getString(5);
                    concerts.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Concert(rs.getString(2),
                            rs.getString(3), LocalDate.parse(rs.getString(4)), time == null ? null : LocalTime.parse(time),
                            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT key, name, checked_at FROM concert_artist")) {
                while (rs.next()) {
                    byArtist.put(rs.getString(1), new ArtistConcerts(rs.getString(2),
                            concerts.getOrDefault(rs.getString(1), List.of()), Instant.ofEpochMilli(rs.getLong(3))));
                }
            }
        }
    }
}
