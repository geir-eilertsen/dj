package dj.domain;

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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private LibraryPort library = new FakeLibrary(tracks("1", "2", "3", "4", "5", "6", "7", "8"));
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

        mix(ids("3"), 1);

        assertEquals(PLAYER, givenToLlm.get().playerId());
        assertEquals(ids("3"), givenToLlm.get().recentTracks());
        assertEquals(seenContext.get(), givenToLlm.get());
    }

    @Test
    void returnsAnEmptyMixForAnEmptyLibrary() {
        library = new FakeLibrary(List.of());

        Mix mix = mix(List.of(), 3);

        assertTrue(mix.tracks().isEmpty());
    }

    @Test
    void propagatesLibraryFailures() {
        library = (count, exclude) -> {
            throw new LibraryUnavailableException("LMS is down", null);
        };

        assertThrows(LibraryUnavailableException.class, () -> mix(List.of(), 3));
    }

    private Mix mix(List<TrackId> seeds, int count) {
        PlaybackContextPort contexts = (playerId, recent) -> {
            PlaybackContext context = new PlaybackContext(playerId, "Test player", recent, LocalTime.NOON);
            seenContext.set(context);
            return context;
        };
        MixService service = new MixService(library, similarity, llm, contexts, executor, 10, LLM_TIMEOUT);
        return service.mix(new MixRequest(PLAYER, seeds, count));
    }

    /** Deterministic "random": library order, minus exclusions. */
    private record FakeLibrary(List<Track> tracks) implements LibraryPort {

        @Override
        public List<Track> randomTracks(int count, Set<TrackId> exclude) {
            return tracks.stream().filter(t -> !exclude.contains(t.id())).limit(count).toList();
        }
    }
}
