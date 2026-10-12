package dj.domain;

import dj.domain.model.Mix;
import dj.domain.model.MixRequest;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.LibraryPort;
import dj.domain.port.LlmPort;
import dj.domain.port.PlaybackContextPort;
import dj.domain.port.SimilarityPort;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides what a player should play next.
 *
 * <p>Seeds are the request's tracks or else the end of the player's queue, whether or not they
 * are in the library: music played straight from a streaming service still has an artist.
 * Seed artists without a profile are looked up on demand, waiting at most
 * {@code seedLookupTimeout}; a slow lookup finishes in the background for later mixes.
 *
 * <p>Candidates are always generated locally: acoustic neighbours first (when a similarity
 * source exists), then library tracks ranked by {@link CandidateScorer}. The LLM only picks and
 * orders among them, under a hard timeout; if it is slow, fails or answers with nothing usable,
 * candidate order is used instead. Only a failing library prevents a mix.
 */
public final class MixService {

    private static final Logger LOG = System.getLogger(MixService.class.getName());

    /** Seeds used when the request has none: the end of the player's queue. */
    private static final int SEEDS_FROM_QUEUE = 5;

    private final LibraryPort library;
    private final SimilarityPort similarity;
    private final CandidateScorer scorer;
    private final LlmPort llm;
    private final PlaybackContextPort playbackContext;
    private final Optional<ArtistEnricher> enricher;
    private final Executor executor;
    private final int candidatePoolSize;
    private final Duration llmTimeout;
    private final Duration seedLookupTimeout;

    /**
     * @param enricher used to look up unknown seed artists; empty if there is no online source
     * @param executor runs the LLM call and seed lookups so they can be abandoned on timeout
     */
    public MixService(LibraryPort library, SimilarityPort similarity, CandidateScorer scorer, LlmPort llm,
                      PlaybackContextPort playbackContext, Optional<ArtistEnricher> enricher, Executor executor,
                      int candidatePoolSize, Duration llmTimeout, Duration seedLookupTimeout) {
        this.library = Objects.requireNonNull(library);
        this.similarity = Objects.requireNonNull(similarity);
        this.scorer = Objects.requireNonNull(scorer);
        this.llm = Objects.requireNonNull(llm);
        this.playbackContext = Objects.requireNonNull(playbackContext);
        this.enricher = Objects.requireNonNull(enricher);
        this.executor = Objects.requireNonNull(executor);
        if (candidatePoolSize < 1) {
            throw new IllegalArgumentException("candidatePoolSize must be positive");
        }
        this.candidatePoolSize = candidatePoolSize;
        this.llmTimeout = Objects.requireNonNull(llmTimeout);
        this.seedLookupTimeout = Objects.requireNonNull(seedLookupTimeout);
    }

    public Mix mix(MixRequest request) {
        PlaybackContext context = playbackContext.contextFor(request.playerId());
        List<Track> candidates = candidates(request, context);
        List<TrackId> chosen = askLlm(context, candidates, request.count());

        List<Track> tracks = TrackSelection.select(candidates, chosen, request.count());
        Mix.Source source = TrackSelection.anyValid(candidates, chosen) ? Mix.Source.LLM : Mix.Source.FALLBACK;
        return new Mix(tracks, source);
    }

    private List<Track> candidates(MixRequest request, PlaybackContext context) {
        int poolSize = Math.max(candidatePoolSize, request.count());
        List<Track> all = library.allTracks();
        Map<TrackId, Track> byId = new HashMap<>();
        all.forEach(t -> byId.putIfAbsent(t.id(), t));

        List<Track> queue = context.queue();
        Map<TrackId, Track> queued = new HashMap<>();
        queue.forEach(t -> queued.putIfAbsent(t.id(), t));

        List<Track> seeds = request.seeds().stream()
                .map(id -> byId.containsKey(id) ? byId.get(id) : queued.get(id))
                .filter(Objects::nonNull)
                .toList();
        if (seeds.isEmpty()) {
            seeds = queue.subList(Math.max(0, queue.size() - SEEDS_FROM_QUEUE), queue.size());
        }
        lookUpSeedArtists(seeds);

        Map<TrackId, Track> pool = new LinkedHashMap<>();
        for (Track track : neighbours(request.seeds(), poolSize)) {
            if (pool.size() < poolSize && !request.seeds().contains(track.id())
                    && !queued.containsKey(track.id())) {
                pool.putIfAbsent(track.id(), track);
            }
        }
        for (Track track : scorer.rank(all, seeds, queue, poolSize)) {
            if (pool.size() == poolSize) {
                break;
            }
            pool.putIfAbsent(track.id(), track);
        }
        return List.copyOf(pool.values());
    }

    private void lookUpSeedArtists(List<Track> seeds) {
        if (enricher.isEmpty() || seeds.isEmpty()) {
            return;
        }
        List<String> artists = seeds.stream().map(Track::artist).distinct().toList();
        CompletableFuture<Void> lookup = CompletableFuture.runAsync(() -> enricher.get().ensure(artists), executor);
        try {
            lookup.get(seedLookupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Not cancelled: let it finish and cache the profiles for the next mix.
            LOG.log(Level.INFO, "Seed artist lookup still running after {0,number,#} ms; mixing without it",
                    seedLookupTimeout.toMillis());
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Seed artist lookup failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Track> neighbours(List<TrackId> seeds, int limit) {
        try {
            return Optional.ofNullable(similarity.neighbours(seeds, limit)).orElse(List.of());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Similarity lookup failed; using library tracks only", e);
            return List.of();
        }
    }

    private List<TrackId> askLlm(PlaybackContext context, List<Track> candidates, int count) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        CompletableFuture<List<TrackId>> answer =
                CompletableFuture.supplyAsync(() -> llm.choose(context, candidates, count), executor);
        try {
            List<TrackId> chosen = answer.get(llmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return chosen == null ? List.of() : chosen;
        } catch (TimeoutException e) {
            answer.cancel(true);
            LOG.log(Level.WARNING, "LLM did not answer within {0,number,#} ms; using candidate order", llmTimeout.toMillis());
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "LLM failed; using candidate order", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }
}
