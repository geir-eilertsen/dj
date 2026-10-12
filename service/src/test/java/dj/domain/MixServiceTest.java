package dj.domain;

import dj.domain.model.ArtistProfile;
import dj.domain.model.Mix;
import dj.domain.model.MixRequest;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.LibraryPort;
import dj.domain.port.LibraryUnavailableException;
import dj.domain.port.LlmPort;
import dj.domain.port.PlaybackContextPort;
import dj.domain.port.SimilarityPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static dj.domain.Tracks.ids;
import static dj.domain.Tracks.tracks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixServiceTest {

    private static final String PLAYER = "aa:bb:cc:dd:ee:ff";
    private static final Duration LLM_TIMEOUT = Duration.ofMillis(200);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private List<Track> libraryTracks = tracks("1", "2", "3", "4", "5", "6", "7", "8");
    private LibraryPort library = () -> libraryTracks;
    private List<Track> queue = List.of();
    private Optional<ArtistEnricher> enricher = Optional.empty();
    private Duration seedLookupTimeout = Duration.ofSeconds(1);
    private final InMemoryProfileStore profiles = new InMemoryProfileStore();
    private SimilarityPort similarity = (seeds, limit) -> List.of();
    private LlmPort llm = (context, candidates, count) -> List.of();
    private final AtomicReference<PlaybackContext> seenContext = new AtomicReference<>();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    @Test
    void returnsTheRequestedNumberOfLibraryTracks() {
        Mix mix = mix(List.of(), 3);

        assertEquals(3, mix.tracks().size());
        assertEquals(Mix.Source.FALLBACK, mix.source());
    }

    @Test
    void neverSuggestsASeedTrack() {
        Mix mix = mix(ids("1", "2"), 5);

        assertEquals(ids("3", "4", "5", "6", "7"), ids(mix.tracks()));
    }

    @Test
    void usesTheLlmOrderWhenItChoosesCandidates() {
        llm = (context, candidates, count) -> ids("7", "3");

        Mix mix = mix(List.of(), 2);

        assertEquals(ids("7", "3"), ids(mix.tracks()));
        assertEquals(Mix.Source.LLM, mix.source());
    }

    @Test
    void discardsLlmIdsThatAreNotCandidates() {
        llm = (context, candidates, count) -> ids("1", "not-a-track", "42");

        Mix mix = mix(ids("1"), 3);

        // "1" is a seed, so not a candidate; the other two don't exist. Nothing usable.
        assertEquals(ids("2", "3", "4"), ids(mix.tracks()));
        assertEquals(Mix.Source.FALLBACK, mix.source());
    }

    @Test
    void fallsBackToCandidateOrderWhenTheLlmFails() {
        llm = (context, candidates, count) -> {
            throw new IllegalStateException("model not loaded");
        };

        Mix mix = mix(List.of(), 2);

        assertEquals(ids("1", "2"), ids(mix.tracks()));
        assertEquals(Mix.Source.FALLBACK, mix.source());
    }

    @Test
    void fallsBackToCandidateOrderWhenTheLlmIsTooSlow() {
        llm = (context, candidates, count) -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ids("8");
        };

        Mix mix = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> mix(List.of(), 2));

        assertEquals(ids("1", "2"), ids(mix.tracks()));
        assertEquals(Mix.Source.FALLBACK, mix.source());
    }

    @Test
    void treatsANullLlmAnswerAsNoAnswer() {
        llm = (context, candidates, count) -> null;

        Mix mix = mix(List.of(), 1);

        assertEquals(ids("1"), ids(mix.tracks()));
    }

    @Test
    void putsSimilarTracksFirstAndTopsUpFromTheLibrary() {
        similarity = (seeds, limit) -> tracks("6", "1", "5");

        Mix mix = mix(ids("1"), 4);

        assertEquals(ids("6", "5", "2", "3"), ids(mix.tracks()));
    }

    @Test
    void neverSuggestsSomethingAlreadyInTheQueue() {
        queue = queued("1", "2", "3");

        Mix mix = mix(ids("3"), 3);

        assertEquals(ids("4", "5", "6"), ids(mix.tracks()));
    }

    @Test
    void ranksLibraryTracksBySimilarityToTheSeeds() {
        libraryTracks = List.of(Tracks.track("1", "Opeth"), Tracks.track("2", "ABBA"), Tracks.track("3", "Katatonia"));
        profiles.with("Opeth", List.of("progressive metal"), "Katatonia:0.9");

        Mix mix = mix(ids("1"), 2);

        assertEquals(ids("3", "2"), ids(mix.tracks()));
    }

    @Test
    void seedsFromTheEndOfTheQueueWhenTheRequestHasNone() {
        libraryTracks = List.of(Tracks.track("1", "Opeth"), Tracks.track("2", "ABBA"), Tracks.track("3", "Katatonia"));
        profiles.with("Opeth", List.of("progressive metal"), "Katatonia:0.9");
        queue = queued("1");

        Mix mix = mix(List.of(), 1);

        assertEquals(ids("3"), ids(mix.tracks()));
    }

    @Test
    void ignoresAFailingSimilaritySource() {
        similarity = (seeds, limit) -> {
            throw new IllegalStateException("bliss database missing");
        };

        Mix mix = mix(List.of(), 2);

        assertEquals(ids("1", "2"), ids(mix.tracks()));
    }

    @Test
    void offersTheLlmTheWholeCandidatePool() {
        List<Track> offered = new ArrayList<>();
        llm = (context, candidates, count) -> {
            offered.addAll(candidates);
            return List.of();
        };

        mix(ids("8"), 2);

        assertEquals(ids("1", "2", "3", "4", "5", "6", "7"), ids(offered));
    }

    @Test
    void passesThePlaybackContextToTheLlm() {
        AtomicReference<PlaybackContext> givenToLlm = new AtomicReference<>();
        llm = (context, candidates, count) -> {
            givenToLlm.set(context);
            return List.of();
        };

        queue = queued("3");

        mix(ids("3"), 1);

        assertEquals(PLAYER, givenToLlm.get().playerId());
        assertEquals(ids("3"), ids(givenToLlm.get().queue()));
        assertEquals(seenContext.get(), givenToLlm.get());
    }

    @Test
    void returnsAnEmptyMixForAnEmptyLibrary() {
        libraryTracks = List.of();

        Mix mix = mix(List.of(), 3);

        assertTrue(mix.tracks().isEmpty());
    }

    @Test
    void propagatesLibraryFailures() {
        library = () -> {
            throw new LibraryUnavailableException("LMS is down", null);
        };

        assertThrows(LibraryUnavailableException.class, () -> mix(List.of(), 3));
    }

    @Test
    void followsMusicPlayedStraightFromAStreamingService() {
        useDanishLibrary();
        profiles.with("Kim Larsen", List.of("danish", "rock"), "Gasolin':0.9");
        queue = List.of(streamed("-100", "Kim Larsen", "Sommer"), streamed("-101", "Kim Larsen", "Frakken"));

        Mix mix = mix(List.of(), 1);

        assertEquals(ids("1"), ids(mix.tracks()));
    }

    @Test
    void resolvesRequestSeedsThatAreOnlyInTheQueue() {
        useDanishLibrary();
        profiles.with("Kim Larsen", List.of("danish", "rock"), "Gasolin':0.9");
        queue = List.of(streamed("-100", "Kim Larsen", "Sommer"), queued("2").getFirst());

        Mix mix = mix(ids("-100"), 1);

        assertEquals(ids("1"), ids(mix.tracks()));
    }

    @Test
    void looksUpSeedArtistsThatHaveNoProfileYet() {
        useDanishLibrary();
        List<String> lookedUp = new ArrayList<>();
        enricher = Optional.of(enricher(name -> {
            lookedUp.add(name);
            return Optional.of(new ArtistProfile(name, List.of(new ArtistProfile.Tag("danish", 1)),
                    List.of(new ArtistProfile.SimilarArtist("Gasolin'", 0.9)), Instant.EPOCH));
        }));
        queue = List.of(streamed("-100", "Kim Larsen", "Sommer"));

        Mix mix = mix(List.of(), 1);

        assertEquals(List.of("Kim Larsen"), lookedUp);
        assertEquals(ids("1"), ids(mix.tracks()));
    }

    @Test
    void doesNotWaitLongForASlowArtistLookup() {
        useDanishLibrary();
        enricher = Optional.of(enricher(name -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }));
        seedLookupTimeout = Duration.ofMillis(100);
        queue = List.of(streamed("-100", "Kim Larsen", "Sommer"));

        Mix mix = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> mix(List.of(), 2));

        assertEquals(2, mix.tracks().size());
    }

    /** Gasolin' (Kim Larsen's band) plus two much more played metal bands. */
    private void useDanishLibrary() {
        libraryTracks = List.of(Tracks.track("1", "Gasolin'", 1975, 1),
                Tracks.track("2", "Opeth", 2005, 50), Tracks.track("3", "TOOL", 2006, 40));
    }

    private static Track streamed(String id, String artist, String title) {
        return new Track(TrackId.of(id), "tidal://" + id.substring(1) + ".flc", title, artist, "", 0, 0);
    }

    private List<Track> queued(String... ids) {
        List<TrackId> wanted = ids(ids);
        return libraryTracks.stream().filter(t -> wanted.contains(t.id())).toList();
    }

    private ArtistEnricher enricher(dj.domain.port.ArtistInfoSource source) {
        return new ArtistEnricher(library, source, profiles, java.time.Clock.systemUTC(), Duration.ofDays(30));
    }

    private Mix mix(List<TrackId> seeds, int count) {
        PlaybackContextPort contexts = playerId -> {
            PlaybackContext context = new PlaybackContext(playerId, "Test player", queue, LocalTime.NOON);
            seenContext.set(context);
            return context;
        };
        CandidateScorer scorer = new CandidateScorer(profiles, ScoringWeights.DEFAULTS.withJitter(0), new Random(0));
        MixService service = new MixService(library, similarity, scorer, llm, contexts, enricher, executor, 10,
                LLM_TIMEOUT, seedLookupTimeout);
        return service.mix(new MixRequest(PLAYER, seeds, count));
    }
}
