package dj.adapter.sqlite;

import dj.domain.model.ArtistProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteArtistProfileStoreTest {

    private static final Instant FETCHED = Instant.parse("2026-10-12T10:00:00Z");

    @TempDir
    Path dir;

    private final ArtistProfile opeth = new ArtistProfile("Opeth",
            List.of(new ArtistProfile.Tag("progressive metal", 1.0), new ArtistProfile.Tag("death metal", 0.7)),
            List.of(new ArtistProfile.SimilarArtist("Katatonia", 0.9), new ArtistProfile.SimilarArtist("Porcupine Tree", 0.6)),
            FETCHED);

    @Test
    void findsSavedProfilesByNormalisedName() {
        SqliteArtistProfileStore store = new SqliteArtistProfileStore(dir.resolve("dj.db"));

        store.save(opeth);

        assertEquals(opeth, store.find("OPETH").orElseThrow());
        assertEquals(1, store.size());
    }

    @Test
    void survivesARestart() {
        Path db = dir.resolve("sub/dir/dj.db");
        new SqliteArtistProfileStore(db).save(opeth);

        SqliteArtistProfileStore reopened = new SqliteArtistProfileStore(db);

        assertEquals(opeth, reopened.find("Opeth").orElseThrow());
    }

    @Test
    void replacesAnExistingProfile() {
        Path db = dir.resolve("dj.db");
        SqliteArtistProfileStore store = new SqliteArtistProfileStore(db);
        store.save(opeth);

        ArtistProfile refreshed = ArtistProfile.unknown("Opeth", FETCHED.plusSeconds(60));
        store.save(refreshed);

        assertEquals(refreshed, store.find("Opeth").orElseThrow());
        assertTrue(new SqliteArtistProfileStore(db).find("Opeth").orElseThrow().isUnknown());
    }
}
