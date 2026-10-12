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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides what a player should play next.
 *
 * <p>Candidates are always generated locally (similarity neighbours, topped up with random
 * library tracks). The LLM only picks and orders among them, under a hard timeout; if it is
 * slow, fails or answers with nothing usable, candidate order is used instead. Only a failing
 * library prevents a mix.
 */
public final class MixService {

    private static final Logger LOG = System.getLogger(MixService.class.getName());

    private final LibraryPort library;
    private final SimilarityPort similarity;
    private final LlmPort llm;
    private final PlaybackContextPort playbackContext;
    private final Executor llmExecutor;
    private final int candidatePoolSize;
    private final Duration llmTimeout;

    public MixService(LibraryPort library, SimilarityPort similarity, LlmPort llm,
                      PlaybackContextPort playbackContext, Executor llmExecutor,
                      int candidatePoolSize, Duration llmTimeout) {
        this.library = Objects.requireNonNull(library);
        this.similarity = Objects.requireNonNull(similarity);
        this.llm = Objects.requireNonNull(llm);
        this.playbackContext = Objects.requireNonNull(playbackContext);
        this.llmExecutor = Objects.requireNonNull(llmExecutor);
        if (candidatePoolSize < 1) {
            throw new IllegalArgumentException("candidatePoolSize must be positive");
        }
        this.candidatePoolSize = candidatePoolSize;
        this.llmTimeout = Objects.requireNonNull(llmTimeout);
    }

    public Mix mix(MixRequest request) {
        PlaybackContext context = playbackContext.contextFor(request.playerId(), request.seeds());
        List<Track> candidates = candidates(request);
        List<TrackId> chosen = askLlm(context, candidates, request.count());

        List<Track> tracks = TrackSelection.select(candidates, chosen, request.count());
        Mix.Source source = TrackSelection.anyValid(candidates, chosen) ? Mix.Source.LLM : Mix.Source.FALLBACK;
        return new Mix(tracks, source);
    }

    private List<Track> candidates(MixRequest request) {
        int poolSize = Math.max(candidatePoolSize, request.count());
        Set<TrackId> exclude = new HashSet<>(request.seeds());
        Map<TrackId, Track> pool = new LinkedHashMap<>();

        for (Track track : neighbours(request.seeds(), poolSize)) {
            if (pool.size() == poolSize) {
                break;
            }
            if (!exclude.contains(track.id())) {
                pool.putIfAbsent(track.id(), track);
            }
        }

        if (pool.size() < poolSize) {
            exclude.addAll(pool.keySet());
            for (Track track : library.randomTracks(poolSize - pool.size(), exclude)) {
                pool.putIfAbsent(track.id(), track);
            }
        }
        return List.copyOf(pool.values());
    }

    private List<Track> neighbours(List<TrackId> seeds, int limit) {
        try {
            return similarity.neighbours(seeds, limit);
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
                CompletableFuture.supplyAsync(() -> llm.choose(context, candidates, count), llmExecutor);
        try {
            List<TrackId> chosen = answer.get(llmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return chosen == null ? List.of() : chosen;
        } catch (TimeoutException e) {
            answer.cancel(true);
            LOG.log(Level.WARNING, "LLM did not answer within {0} ms; using candidate order", llmTimeout.toMillis());
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "LLM failed; using candidate order", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }
}
