package dj.adapter.sqlite;

import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.model.ConcertLookup;
import dj.domain.port.ConcertProviderChoices;
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

/** Concert lookups and the listener's choice of providers in a local SQLite database, mirrored in memory. */
public class SqliteConcertStore implements ConcertStore, ConcertProviderChoices {

    /**
     * 2: lookups made before Madness Live was a source are dropped, so the next scan asks again.
     * 3: lookups are per provider; the merged ones are dropped.
     */
    private static final int SCHEMA_VERSION = 3;

    private final String url;
    /** {@code <artist key>|<provider>} → lookup */
    private final Map<String, ConcertLookup> lookups = new ConcurrentHashMap<>();
    private volatile Map<String, Boolean> choices = Map.of();

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
    public Optional<ConcertLookup> get(String artist, String provider) {
        return Optional.ofNullable(lookups.get(key(ArtistNames.key(artist), provider)));
    }

    @Override
    public List<ConcertLookup> all() {
        return List.copyOf(lookups.values());
    }

    @Override
    public synchronized void save(ConcertLookup lookup) {
        String artistKey = ArtistNames.key(lookup.artist());
        try (Connection db = DriverManager.getConnection(url)) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = db.prepareStatement(
                        "INSERT INTO concert_lookup (artist_key, provider, name, checked_at) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (artist_key, provider) DO UPDATE SET name = excluded.name, "
                                + "checked_at = excluded.checked_at")) {
                    upsert.setString(1, artistKey);
                    upsert.setString(2, lookup.provider());
                    upsert.setString(3, lookup.artist());
                    upsert.setLong(4, lookup.checkedAt().toEpochMilli());
                    upsert.executeUpdate();
                }
                try (PreparedStatement delete = db.prepareStatement(
                        "DELETE FROM concert WHERE artist_key = ? AND provider = ?")) {
                    delete.setString(1, artistKey);
                    delete.setString(2, lookup.provider());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO concert (artist_key, provider, position, title, date, time, venue, city, country, url) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int position = 0;
                    for (Concert c : lookup.concerts()) {
                        insert.setString(1, artistKey);
                        insert.setString(2, lookup.provider());
                        insert.setInt(3, position++);
                        insert.setString(4, c.title());
                        insert.setString(5, c.date().toString());
                        if (c.time() == null) {
                            insert.setNull(6, Types.VARCHAR);
                        } else {
                            insert.setString(6, c.time().toString());
                        }
                        insert.setString(7, c.venue());
                        insert.setString(8, c.city());
                        insert.setString(9, c.countryCode());
                        insert.setString(10, c.url());
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
            throw new IllegalStateException("Could not save concerts for " + lookup.artist(), e);
        }
        lookups.put(key(artistKey, lookup.provider()), lookup);
    }

    @Override
    public Map<String, Boolean> choices() {
        return choices;
    }

    @Override
    public synchronized void save(Map<String, Boolean> choices) {
        try (Connection db = DriverManager.getConnection(url)) {
            db.setAutoCommit(false);
            try {
                try (Statement st = db.createStatement()) {
                    st.executeUpdate("DELETE FROM concert_provider_choice");
                }
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO concert_provider_choice (provider, enabled) VALUES (?, ?)")) {
                    for (Map.Entry<String, Boolean> choice : choices.entrySet()) {
                        insert.setString(1, choice.getKey());
                        insert.setInt(2, choice.getValue() ? 1 : 0);
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
            throw new IllegalStateException("Could not save the choice of concert providers", e);
        }
        this.choices = Map.copyOf(choices);
    }

    private static String key(String artistKey, String provider) {
        return artistKey + "|" + provider;
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
            st.executeUpdate("DROP TABLE IF EXISTS concert");
            st.executeUpdate("DROP TABLE IF EXISTS concert_artist");
            st.executeUpdate("""
                    CREATE TABLE concert_lookup (
                        artist_key TEXT NOT NULL,
                        provider   TEXT NOT NULL,
                        name       TEXT NOT NULL,
                        checked_at INTEGER NOT NULL,
                        PRIMARY KEY (artist_key, provider)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE concert (
                        artist_key TEXT NOT NULL,
                        provider   TEXT NOT NULL,
                        position   INTEGER NOT NULL,
                        title      TEXT NOT NULL,
                        date       TEXT NOT NULL,
                        time       TEXT,
                        venue      TEXT NOT NULL,
                        city       TEXT NOT NULL,
                        country    TEXT NOT NULL,
                        url        TEXT NOT NULL,
                        PRIMARY KEY (artist_key, provider, position),
                        FOREIGN KEY (artist_key, provider) REFERENCES concert_lookup (artist_key, provider)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS concert_provider_choice (
                        provider TEXT PRIMARY KEY,
                        enabled  INTEGER NOT NULL
                    )""");
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private void loadAll(Connection db) throws SQLException {
        Map<String, List<Concert>> concerts = new HashMap<>();
        try (Statement st = db.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT l.artist_key, l.provider, l.name, c.title, c.date, c.time, c.venue, c.city, c.country, c.url "
                            + "FROM concert c JOIN concert_lookup l ON l.artist_key = c.artist_key AND l.provider = c.provider "
                            + "ORDER BY c.artist_key, c.provider, c.position")) {
                while (rs.next()) {
                    String time = rs.getString(6);
                    concerts.computeIfAbsent(key(rs.getString(1), rs.getString(2)), k -> new ArrayList<>()).add(
                            new Concert(rs.getString(3), rs.getString(4), LocalDate.parse(rs.getString(5)),
                                    time == null ? null : LocalTime.parse(time), rs.getString(7), rs.getString(8),
                                    rs.getString(9), rs.getString(10)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT artist_key, provider, name, checked_at FROM concert_lookup")) {
                while (rs.next()) {
                    String key = key(rs.getString(1), rs.getString(2));
                    lookups.put(key, new ConcertLookup(rs.getString(3), rs.getString(2),
                            concerts.getOrDefault(key, List.of()), Instant.ofEpochMilli(rs.getLong(4))));
                }
            }
            Map<String, Boolean> loaded = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT provider, enabled FROM concert_provider_choice")) {
                while (rs.next()) {
                    loaded.put(rs.getString(1), rs.getInt(2) != 0);
                }
            }
            choices = Map.copyOf(loaded);
        }
    }
}
