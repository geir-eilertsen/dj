package dj.domain;

import dj.domain.model.ArtistProfile;
import dj.domain.port.ArtistInfoSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtistEnricherTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final InMemoryProfileStore store = new InMemoryProfileStore();
    private final List<String> fetched = new ArrayList<>();
    private List<dj.domain.model.Track> library = List.of(
            track("1", "Opeth"), track("2", "OPETH"), track("3", "Katatonia"), track("4", "ABBA"));

    @Test
    void fetchesEachLibraryArtistOnce() {
        ArtistEnricher.Result result = enricher(this::found).enrich();

        assertEquals(List.of("Opeth", "Katatonia", "ABBA"), fetched);
        assertEquals(3, result.fetched());
        assertEquals(3, store.size());
    }

    @Test
    void skipsFreshProfilesAndRefreshesStaleOnes() {
        store.save(profile("Opeth", NOW.minus(Duration.ofDays(1))));
        store.save(profile("Katatonia", NOW.minus(Duration.ofDays(40))));

        ArtistEnricher.Result result = enricher(this::found).enrich();

        assertEquals(List.of("Katatonia", "ABBA"), fetched);
        assertEquals(1, result.upToDate());
    }

    @Test
    void remembersArtistsTheSourceDoesNotKnow() {
        enricher(name -> {
            fetched.add(name);
            return Optional.empty();
        }).enrich();

        assertTrue(store.find("ABBA").orElseThrow().isUnknown());

        fetched.clear();
        enricher(this::found).enrich();
        assertEquals(List.of(), fetched);
    }

    @Test
    void savesProfilesUnderTheLibrarySpelling() {
        enricher(name -> Optional.of(new ArtistProfile("Autocorrected " + name,
                List.of(new ArtistProfile.Tag("rock", 1)), List.of(), NOW))).enrich();

        assertTrue(store.find("Katatonia").isPresent());
    }

    @Test
    void carriesOnAfterAFailure() {
        ArtistEnricher.Result result = enricher(name -> {
            if (name.equals("Katatonia")) {
                throw new IllegalStateException("HTTP 500");
            }
            return found(name);
        }).enrich();

        assertEquals(2, result.fetched());
        assertEquals(1, result.failed());
        assertFalse(result.aborted());
        assertTrue(store.find("Katatonia").isEmpty());
    }

    @Test
    void stopsAfterTooManyFailuresInARow() {
        library = java.util.stream.IntStream.range(0, 20).mapToObj(i -> track(String.valueOf(i), "Artist " + i)).toList();

        ArtistEnricher.Result result = enricher(name -> {
            fetched.add(name);
            throw new IllegalStateException("no network");
        }).enrich();

        assertTrue(result.aborted());
        assertEquals(ArtistEnricher.MAX_CONSECUTIVE_FAILURES, fetched.size());
    }

    @Test
    void ensureFetchesOnlyMissingOrStaleArtists() {
        store.save(profile("Opeth", NOW.minus(Duration.ofDays(1))));

        enricher(this::found).ensure(List.of("Kim Larsen", "OPETH", "Kim Larsen", ""));

        assertEquals(List.of("Kim Larsen"), fetched);
        assertTrue(store.find("kim larsen").isPresent());
    }

    @Test
    void ensureDoesNotRetryAFailedArtistStraightAway() {
        ArtistEnricher enricher = enricher(name -> {
            fetched.add(name);
            throw new IllegalStateException("no network");
        });

        enricher.ensure(List.of("Kim Larsen"));
        enricher.ensure(List.of("Kim Larsen"));

        assertEquals(List.of("Kim Larsen"), fetched);
    }

    private Optional<ArtistProfile> found(String name) {
        fetched.add(name);
        return Optional.of(profile(name, NOW));
    }

    private static ArtistProfile profile(String name, Instant fetchedAt) {
        return new ArtistProfile(name, List.of(new ArtistProfile.Tag("rock", 1)), List.of(), fetchedAt);
    }

    private ArtistEnricher enricher(ArtistInfoSource source) {
        return new ArtistEnricher(() -> library, source, store, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    }
}
