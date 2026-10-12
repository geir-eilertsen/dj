package dj.domain;

import dj.domain.model.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static dj.domain.Tracks.ids;
import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateScorerTest {

    private final InMemoryProfileStore profiles = new InMemoryProfileStore()
            .with("Opeth", List.of("progressive metal", "death metal"), "Katatonia:0.9", "Porcupine Tree:0.6")
            .with("Katatonia", List.of("doom metal", "progressive metal"), "Opeth:0.85")
            .with("Porcupine Tree", List.of("progressive rock"), "Opeth:0.5")
            .with("Steven Wilson", List.of("progressive rock", "progressive metal"))
            .with("ABBA", List.of("pop", "disco"));

    private final Track opethSeed = track("1", "Opeth", 2005, 3);
    private final Track katatonia = track("10", "Katatonia", 2006, 0);
    private final Track porcupineTree = track("11", "Porcupine Tree", 2007, 0);
    private final Track stevenWilson = track("12", "Steven Wilson", 2015, 0);
    private final Track abba = track("13", "ABBA", 1976, 0);
    private final Track unknownArtist = track("14", "Nobody Knows", 0, 0);

    @Test
    void ranksSimilarArtistsFirstThenSharedTagsThenTheRest() {
        List<Track> ranked = scorer().rank(
                List.of(abba, unknownArtist, stevenWilson, porcupineTree, katatonia),
                List.of(opethSeed), List.of(opethSeed), 10);

        assertEquals(ids("10", "11", "12", "13", "14"), Tracks.ids(ranked));
    }

    @Test
    void usesSimilarityFromEitherArtistsProfile() {
        // Only Steven Wilson's (hypothetical) profile mentions Opeth.
        profiles.with("Steven Wilson", List.of(), "Opeth:0.95");

        List<Track> ranked = scorer().rank(List.of(porcupineTree, stevenWilson), List.of(opethSeed), List.of(), 2);

        assertEquals(ids("12", "11"), Tracks.ids(ranked));
    }

    @Test
    void neverReturnsQueuedTracksOrTheSameSongFromAnotherService() {
        Track opethOnTidal = new Track(dj.domain.model.TrackId.of("2"), "tidal://1.flc", opethSeed.title(),
                "OPETH", "Other album", 2005, 0);
        Track queued = track("3", "Katatonia");

        List<Track> ranked = scorer().rank(List.of(opethSeed, opethOnTidal, queued, katatonia),
                List.of(opethSeed), List.of(queued, opethSeed), 10);

        assertEquals(ids("10"), Tracks.ids(ranked));
    }

    @Test
    void penalisesArtistsHeardInTheLastFewTracks() {
        Track queuedKatatonia = track("20", "Katatonia");

        List<Track> ranked = scorer().rank(List.of(katatonia, porcupineTree),
                List.of(opethSeed), List.of(queuedKatatonia, opethSeed), 2);

        assertEquals(ids("11", "10"), Tracks.ids(ranked));
    }

    @Test
    void spreadsTheTopOfTheListAcrossArtists() {
        List<Track> library = List.of(
                track("30", "Katatonia"), track("31", "Katatonia"), track("32", "Katatonia"),
                track("40", "Porcupine Tree"), track("41", "Porcupine Tree"));

        List<Track> ranked = scorer().rank(library, List.of(opethSeed), List.of(), 3);

        // Without spreading this would be three Katatonia tracks.
        assertEquals(List.of("Katatonia", "Katatonia", "Porcupine Tree"),
                ranked.stream().map(Track::artist).toList());
    }

    @Test
    void prefersMorePlayedTracksWhenThereAreNoSeeds() {
        List<Track> library = List.of(track("50", "A", 0, 1), track("51", "B", 0, 40), track("52", "C", 0, 0));

        List<Track> ranked = scorer().rank(library, List.of(), List.of(), 3);

        assertEquals(ids("51", "50", "52"), Tracks.ids(ranked));
    }

    @Test
    void prefersCloserReleaseYearsAmongOtherwiseEqualTracks() {
        List<Track> library = List.of(track("60", "X", 1980, 0), track("61", "Y", 2004, 0));

        List<Track> ranked = scorer().rank(library, List.of(opethSeed), List.of(), 2);

        assertEquals(ids("61", "60"), Tracks.ids(ranked));
    }

    @Test
    void respectsTheLimit() {
        List<Track> ranked = scorer().rank(List.of(abba, katatonia, porcupineTree), List.of(opethSeed), List.of(), 2);

        assertEquals(2, ranked.size());
    }

    @Test
    void jitterVariesTheOrderOfOtherwiseEqualTracks() {
        List<Track> library = java.util.stream.IntStream.range(100, 130)
                .mapToObj(i -> track(String.valueOf(i), "Artist " + i)).toList();
        CandidateScorer noisy = new CandidateScorer(profiles, ScoringWeights.DEFAULTS, new Random(1));

        List<Track> first = noisy.rank(library, List.of(), List.of(), 10);
        List<Track> second = noisy.rank(library, List.of(), List.of(), 10);

        assertNotEquals(first, second);
        assertFalse(first.isEmpty());
    }

    @Test
    void unknownSeedArtistsStillProduceCandidates() {
        Track seed = track("70", "Never Heard Of Them", 2000, 0);

        List<Track> ranked = scorer().rank(List.of(abba, katatonia), List.of(seed), List.of(seed), 5);

        assertEquals(2, ranked.size());
        assertTrue(ranked.containsAll(List.of(abba, katatonia)));
    }

    /** No jitter, so rankings are deterministic. */
    private CandidateScorer scorer() {
        return new CandidateScorer(profiles, ScoringWeights.DEFAULTS.withJitter(0), new Random(0));
    }
}
