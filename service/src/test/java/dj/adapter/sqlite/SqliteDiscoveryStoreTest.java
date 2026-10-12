package dj.adapter.sqlite;

import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteDiscoveryStoreTest {

    private static final Instant AT = Instant.parse("2026-10-12T10:00:00Z");

    @TempDir
    Path dir;

    private static Track tidal(String id, String title, String artist) {
        String url = "tidal://" + id + ".flc";
        return new Track(TrackId.of(url), url, title, artist, "", 0, 0);
    }

    @Test
    void storesTracksPerArtistAndSurvivesARestart() {
        Path db = dir.resolve("discovery.db");
        SqliteDiscoveryStore store = new SqliteDiscoveryStore(db);
        List<Track> tv2 = List.of(tidal("1", "Bag Duggede Ruder", "TV-2"), tidal("2", "Rejsen til Rom", "TV-2"));
        store.save("TV-2", tv2, AT);
        store.save("Shu-bi-dua", List.of(tidal("3", "Den Røde Tråd", "Shu-bi-dua")), AT);
        store.rememberListened("Kim Larsen", AT);

        SqliteDiscoveryStore reopened = new SqliteDiscoveryStore(db);

        assertEquals(3, reopened.allTracks().size());
        assertEquals(tv2, reopened.allTracks().stream().filter(t -> t.artist().equals("TV-2")).toList());
        assertEquals(Optional.of(AT), reopened.discoveredAt("tv-2"));
        assertEquals(List.of("Kim Larsen"), reopened.listenedArtists());
    }

    @Test
    void replacesAnArtistsTracks() {
        SqliteDiscoveryStore store = new SqliteDiscoveryStore(dir.resolve("discovery.db"));
        store.save("TV-2", List.of(tidal("1", "Bag Duggede Ruder", "TV-2")), AT);

        store.save("TV-2", List.of(), AT.plusSeconds(60));

        assertEquals(List.of(), store.allTracks());
        assertEquals(Optional.of(AT.plusSeconds(60)), store.discoveredAt("TV-2"));
    }
}
