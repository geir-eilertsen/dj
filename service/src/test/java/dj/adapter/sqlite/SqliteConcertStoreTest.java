package dj.adapter.sqlite;

import dj.domain.model.Concert;
import dj.domain.model.ConcertLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteConcertStoreTest {

    private static final Instant AT = Instant.parse("2026-10-13T10:00:00Z");

    @TempDir
    Path dir;

    @Test
    void storesLookupsPerArtistAndProviderAndSurvivesARestart() {
        Path db = dir.resolve("concerts.db");
        SqliteConcertStore store = new SqliteConcertStore(db);
        List<Concert> opeth = List.of(
                new Concert("Opeth", "Tour", LocalDate.parse("2026-11-14"), LocalTime.of(20, 0), "Sentrum Scene", "Oslo",
                        "NO", "https://tm/1"),
                new Concert("Opeth", "Tour", LocalDate.parse("2026-12-01"), null, "Roundhouse", "London", "GB", ""));
        List<Concert> promoter = List.of(
                new Concert("Opeth", "Opeth + Grand Magus", LocalDate.parse("2027-01-20"), null, "Razzmatazz", "Barcelona",
                        "ES", "https://ml/1"));
        store.save(new ConcertLookup("Opeth", "ticketmaster", List.of(new Concert("Opeth", "Old",
                LocalDate.parse("2026-10-20"), null, "X", "Y", "NO", "")), AT.minusSeconds(60)));
        store.save(new ConcertLookup("Opeth", "ticketmaster", opeth, AT));
        store.save(new ConcertLookup("Opeth", "madness-live", promoter, AT));
        store.save(new ConcertLookup("Gasolin'", "ticketmaster", List.of(), AT));

        SqliteConcertStore reopened = new SqliteConcertStore(db);

        assertEquals(Optional.of(new ConcertLookup("Opeth", "ticketmaster", opeth, AT)), reopened.get("OPETH", "ticketmaster"));
        assertEquals(Optional.of(new ConcertLookup("Opeth", "madness-live", promoter, AT)), reopened.get("opeth", "madness-live"));
        assertEquals(Optional.of(new ConcertLookup("Gasolin'", "ticketmaster", List.of(), AT)), reopened.get("gasolin", "ticketmaster"));
        assertEquals(Optional.empty(), reopened.get("Gasolin'", "madness-live"));
        assertEquals(3, reopened.all().size());
    }

    @Test
    void remembersTheChoiceOfProviders() {
        Path db = dir.resolve("concerts.db");
        new SqliteConcertStore(db).save(Map.of("ticketmaster", true, "folken", false));
        new SqliteConcertStore(db).save(Map.of("ticketmaster", false, "madness-live", true));

        assertEquals(Map.of("ticketmaster", false, "madness-live", true), new SqliteConcertStore(db).choices());
    }

    @Test
    void forgetsMergedLookupsFromBeforeProviders() throws Exception {
        Path db = dir.resolve("concerts.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE concert_artist (key TEXT PRIMARY KEY, name TEXT NOT NULL, checked_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE concert (artist_key TEXT NOT NULL, position INTEGER NOT NULL, title TEXT NOT NULL, "
                    + "date TEXT NOT NULL, time TEXT, venue TEXT NOT NULL, city TEXT NOT NULL, country TEXT NOT NULL, "
                    + "url TEXT NOT NULL, PRIMARY KEY (artist_key, position))");
            st.executeUpdate("INSERT INTO concert_artist VALUES ('enslaved', 'Enslaved', 1)");
            st.executeUpdate("PRAGMA user_version = 2");
        }
        SqliteConcertStore store = new SqliteConcertStore(db);
        assertEquals(List.of(), store.all());
        assertEquals(Map.of(), store.choices());
    }
}
