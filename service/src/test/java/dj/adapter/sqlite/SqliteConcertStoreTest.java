package dj.adapter.sqlite;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.Concert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteConcertStoreTest {

    private static final Instant AT = Instant.parse("2026-10-13T10:00:00Z");

    @TempDir
    Path dir;

    @Test
    void storesLookupsPerArtistAndSurvivesARestart() {
        Path db = dir.resolve("concerts.db");
        SqliteConcertStore store = new SqliteConcertStore(db);
        List<Concert> opeth = List.of(
                new Concert("Opeth", "Tour", LocalDate.parse("2026-11-14"), LocalTime.of(20, 0), "Sentrum Scene", "Oslo",
                        "NO", "https://tm/1"),
                new Concert("Opeth", "Tour", LocalDate.parse("2026-12-01"), null, "Roundhouse", "London", "GB", ""));
        store.save(new ArtistConcerts("Opeth", List.of(new Concert("Opeth", "Old", LocalDate.parse("2026-10-20"), null,
                "X", "Y", "NO", "")), AT.minusSeconds(60)));
        store.save(new ArtistConcerts("Opeth", opeth, AT));
        store.save(new ArtistConcerts("Gasolin'", List.of(), AT));

        SqliteConcertStore reopened = new SqliteConcertStore(db);

        assertEquals(Optional.of(new ArtistConcerts("Opeth", opeth, AT)), reopened.get("OPETH"));
        assertEquals(Optional.of(new ArtistConcerts("Gasolin'", List.of(), AT)), reopened.get("gasolin"));
        assertEquals(2, reopened.all().size());
    }

    @Test
    void forgetsLookupsFromBeforeMadnessLive() throws Exception {
        Path db = dir.resolve("concerts.db");
        new SqliteConcertStore(db).save(new ArtistConcerts("Enslaved", List.of(), AT));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("PRAGMA user_version = 1");
        }
        assertEquals(Optional.empty(), new SqliteConcertStore(db).get("Enslaved"));
    }
}
