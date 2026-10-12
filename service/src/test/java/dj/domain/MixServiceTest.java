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
    private final InMemoryDiscoveryStore discovered = new InMemoryDiscoveryStore();
    private Optional<Discoverer> discoverer = Optional.empty();
    private double discoveryShare = 0.4;
    private boolean playing = true;
    private final InMemoryPlayHistory history = new InMemoryPlayHistory();
    private final InMemoryFeedbackStore feedback = new InMemoryFeedbackStore();
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
    void remembersWhatItSuggestedToWhichPlayer() {
        Mix mix = mix(List.of(), 2);

        assertEquals(mix.tracks().stream().map(Track::url).toList(),
                feedback.suggestions.stream().map(InMemoryFeedbackStore.Suggestion::url).toList());
        assertTrue(feedback.suggestions.stream().allMatch(s -> s.playerId().equals(PLAYER)));
    }

    @Test
    void neverSuggestsASeedTrack() {
        Mix mix = mix(ids("1", "2"), 5);

        assertEquals(ids("3", "4", "5", "6", "7"), ids(mix.tracks()));
    }

    @Test
    void usesTheLlmOrderWhenItChoosesCandidates() {
        llm = (context, candidates, count) -> picks("7", "3");

        Mix mix = mix(List.of(), 2);

        assertEquals(ids("7", "3"), ids(mix.tracks()));
        assertEquals(Mix.Source.LLM, mix.source());
    }

    @Test
    void discardsLlmIdsThatAreNotCandidates() {
        llm = (context, candidates, count) -> picks("1", "not-a-track", "42");

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
            return picks("8");
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

    @Test
    void mixesInRelevantDiscoveredMusic() {
        useDanishLibrary();
        libraryTracks = new ArrayList<>(libraryTracks);
        libraryTracks.add(Tracks.track("4", "Gasolin'", 1976, 1));
        libraryTracks.add(Tracks.track("5", "Gasolin'", 1977, 1));
        profiles.with("Kim Larsen", List.of("danish", "rock"), "Gasolin':0.9", "TV-2:0.8", "Shu-bi-dua:0.7");
        discovered.save("TV-2", List.of(streamed("-200", "TV-2", "Bag Duggede Ruder")), Instant.EPOCH);
        discovered.save("Shu-bi-dua", List.of(streamed("-201", "Shu-bi-dua", "Den Røde Tråd")), Instant.EPOCH);
        discovered.save("ABBA", List.of(streamed("-202", "ABBA", "Waterloo")), Instant.EPOCH);
        queue = List.of(streamed("-100", "Kim Larsen", "Sommer"));

        Mix mix = mix(List.of(), 5);

        // 2 of 5 discovered; ABBA doesn't relate to Kim Larsen; one track per artist.
        List<String> artists = mix.tracks().stream().map(Track::artist).toList();
        assertEquals(List.of("Gasolin'", "TV-2", "Shu-bi-dua", "Opeth", "TOOL"), artists);
    }

    @Test
    void leavesDiscoveredMusicOutWhenTheShareIsZero() {
        useDanishLibrary();
        profiles.with("Kim Larsen", List.of("danish"), "TV-2:0.8");
        discovered.save("TV-2", List.of(streamed("-200", "TV-2", "Bag Duggede Ruder")), Instant.EPOCH);
        queue = List.of(streamed("-100", "Kim Larsen", "Sommer"));
        discoveryShare = 0;

        Mix mix = mix(List.of(), 3);

        assertTrue(mix.tracks().stream().noneMatch(t -> t.artist().equals("TV-2")));
    }

    @Test
    void startsDiscoveryAroundArtistsPlayedFromAStreamingService() throws Exception {
        useDanishLibrary();
        java.util.concurrent.CountDownLatch asked = new java.util.concurrent.CountDownLatch(1);
        List<String> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
        ArtistEnricher noLookups = enricher(name -> Optional.empty());
        discoverer = Optional.of(new Discoverer(library, profiles, noLookups, (artist, limit) -> {
            askedFor.add(artist);
            asked.countDown();
            return List.of();
        }, (artist, title) -> Optional.empty(), discovered, java.time.Clock.systemUTC(), Duration.ofDays(30)));
        queue = List.of(queued("2").getFirst(), streamed("-100", "Kim Larsen", "Sommer"));

        mix(List.of(), 1);

        assertTrue(asked.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(List.of("Kim Larsen"), discovered.listenedArtists());
        assertEquals("Kim Larsen", askedFor.getFirst());
    }

    @Test
    void interleavesLibraryAndDiscoveredTracks() {
        List<Track> lib = tracks("l1", "l2", "l3", "l4", "l5", "l6");
        List<Track> disc = tracks("d1", "d2", "d3");

        assertEquals(ids("l1", "l2", "d1", "l3", "d2", "l4", "l5", "d3", "l6"),
                ids(MixService.interleave(lib, disc, 0.4)));
        assertEquals(ids("l1", "l2", "d1", "l3", "l4", "l5", "l6"), ids(MixService.interleave(lib, tracks("d1"), 0.4)));
        assertEquals(ids("d1", "d2", "d3"), ids(MixService.interleave(List.of(), disc, 0.4)));
        assertEquals(ids(lib), ids(MixService.interleave(lib, disc, 0)).subList(0, 6));
    }

    @Test
    void startsAStoppedPlayerFromWhatItPlaysAtThisTimeOfDay() {
        useDanishLibrary();
        profiles.with("Kim Larsen", List.of("danish"), "Gasolin':0.9");
        history.record(new dj.domain.model.Play(PLAYER, Instant.now().minus(Duration.ofDays(7)),
                streamed("-100", "Kim Larsen", "Sommer")));
        playing = false;
        queue = queued("2", "3");

        // The request seed (Opeth, the stopped player's current track) is ignored.
        Mix mix = mix(ids("2"), 1);

        assertEquals(ids("1"), ids(mix.tracks()));
    }

    @Test
    void aStoppedPlayerWithoutHistoryUsesTheRequestSeeds() {
        useDanishLibrary();
        profiles.with("Opeth", List.of("metal"), "TOOL:0.9");
        playing = false;

        Mix mix = mix(ids("2"), 1);

        assertEquals(ids("3"), ids(mix.tracks()));
    }

    @Test
    void keepsTheLlmsReasonsForTheTracksItPicked() {
        llm = (context, candidates, count) -> List.of(
                new dj.domain.model.Pick(TrackId.of("7"), "Seventh heaven"),
                new dj.domain.model.Pick(TrackId.of("invented"), "Not a candidate"),
                new dj.domain.model.Pick(TrackId.of("3"), ""));

        Mix mix = mix(List.of(), 3);

        assertEquals(ids("7", "3", "1"), ids(mix.tracks()));
        assertEquals(java.util.Map.of(TrackId.of("7"), "Seventh heaven"), mix.reasons());
        assertEquals(java.util.Map.of(mix.tracks().getFirst().url(), "Seventh heaven"), feedback.reasons);
    }

    private static List<dj.domain.model.Pick> picks(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new dj.domain.model.Pick(TrackId.of(id), "because")).toList();
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
            PlaybackContext context = new PlaybackContext(playerId, "Test player", playing, queue, java.time.LocalDateTime.of(2026, 10, 11, 12, 0));
            seenContext.set(context);
            return context;
        };
        CandidateScorer scorer = new CandidateScorer(profiles, ScoringWeights.DEFAULTS.withJitter(0), new Random(0));
        MixService service = new MixService(library, discovered, similarity, scorer, llm, contexts, enricher,
                discoverer, new TimeOfDaySeeds(history, java.time.Clock.systemDefaultZone(), new Random(0)), feedback,
                java.time.Clock.systemUTC(), executor,
                new MixSettings(10, LLM_TIMEOUT, seedLookupTimeout, discoveryShare));
        return service.mix(new MixRequest(PLAYER, seeds, count));
    }
}
