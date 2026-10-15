package dj.domain;

import dj.domain.model.Mix;
import dj.domain.model.MixBrief;
import dj.domain.model.MixRequest;
import dj.domain.model.Mood;
import dj.domain.model.Pick;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Taste;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.DiscoveryStore;
import dj.domain.port.FeedbackStore;
import dj.domain.port.LibraryPort;
import dj.domain.port.LlmPort;
import dj.domain.port.PlaybackContextPort;
import dj.domain.port.SimilarityPort;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.ArrayList;
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
import java.util.function.Predicate;

/**
 * Decides what a player should play next.
 *
 * <p>A stopped player, or a request for a fresh start, starts from scratch: with a mood set
 * ({@link MoodService}) the mood alone leads, otherwise songs the player played around this time
 * of day ({@link TimeOfDaySeeds}) are the seeds. Otherwise seeds are the request's tracks or else
 * the end of the player's queue, whether or not they are in the library: music played straight
 * from a streaming service still has an artist. A mood also steers mixes that follow the queue,
 * until most of the end of the queue is music the listener chose instead of DJ's suggestions:
 * then the mood ends.
 * Seed and mood artists without a profile are looked up on demand, waiting at most
 * {@link MixSettings#seedLookupTimeout()}; a slow lookup finishes in the background for later
 * mixes. Artists played from a streaming service also trigger background discovery.
 *
 * <p>Candidates are always generated locally: acoustic neighbours first (when a similarity
 * source exists), then library tracks and discovered tracks ranked by {@link CandidateScorer}
 * and interleaved so about {@link MixSettings#discoveryShare()} of the mix is discovered music,
 * when relevant discovered music exists. Christmas songs are left out outside the season unless
 * the listener asks for them ({@link ChristmasSongs}); a mood asking for them puts them first. The LLM only picks and orders among the
 * candidates, under a hard timeout; if it is slow, fails or answers with nothing usable, candidate order is
 * used instead. Only a failing library prevents a mix.
 */
public final class MixService {

    private static final Logger LOG = System.getLogger(MixService.class.getName());

    /** Seeds used when the request has none: the end of the player's queue. */
    private static final int SEEDS_FROM_QUEUE = 5;

    private final LibraryPort library;
    private final DiscoveryStore discovered;
    private final SimilarityPort similarity;
    private final CandidateScorer scorer;
    private final LlmPort llm;
    private final PlaybackContextPort playbackContext;
    private final Optional<ArtistEnricher> enricher;
    private final Optional<Discoverer> discoverer;
    private final TimeOfDaySeeds timeOfDaySeeds;
    private final ListenerTaste taste;
    private final MoodService moods;
    private final FeedbackStore feedback;
    private final Clock clock;
    private final Executor executor;
    private final MixSettings settings;

    /**
     * @param enricher   looks up unknown seed artists; empty if there is no online source
     * @param discoverer finds music around artists played from a streaming service; empty if disabled
     * @param taste      the listener's favourite artists, which steer a mood started from scratch
     * @param executor   runs the LLM call, seed lookups and discovery so they can be abandoned or left running
     */
    public MixService(LibraryPort library, DiscoveryStore discovered, SimilarityPort similarity,
                      CandidateScorer scorer, LlmPort llm, PlaybackContextPort playbackContext,
                      Optional<ArtistEnricher> enricher, Optional<Discoverer> discoverer,
                      TimeOfDaySeeds timeOfDaySeeds, ListenerTaste taste, MoodService moods, FeedbackStore feedback, Clock clock,
                      Executor executor, MixSettings settings) {
        this.library = Objects.requireNonNull(library);
        this.discovered = Objects.requireNonNull(discovered);
        this.similarity = Objects.requireNonNull(similarity);
        this.scorer = Objects.requireNonNull(scorer);
        this.llm = Objects.requireNonNull(llm);
        this.playbackContext = Objects.requireNonNull(playbackContext);
        this.enricher = Objects.requireNonNull(enricher);
        this.discoverer = Objects.requireNonNull(discoverer);
        this.timeOfDaySeeds = Objects.requireNonNull(timeOfDaySeeds);
        this.taste = Objects.requireNonNull(taste);
        this.moods = Objects.requireNonNull(moods);
        this.feedback = Objects.requireNonNull(feedback);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.settings = Objects.requireNonNull(settings);
    }

    public Mix mix(MixRequest request) {
        PlaybackContext context = playbackContext.contextFor(request.playerId());
        boolean fresh = request.fresh() || !context.playing();
        Optional<Mood> mood = currentMood(request.playerId());
        if (!fresh && mood.isPresent() && listenerTookOver(request.playerId(), context.queue(), mood.get())) {
            endMood(request.playerId(), mood.get());
            mood = Optional.empty();
        }
        MixBrief brief = new MixBrief(context, fresh, mood,
                fresh && mood.isPresent() ? tasteOf(request.playerId()) : Taste.NONE);
        List<Track> candidates = candidates(request, brief);
        List<Pick> picks = askLlm(brief, candidates, request.count());
        List<TrackId> chosen = picks.stream().map(Pick::id).toList();

        List<Track> tracks = TrackSelection.select(candidates, chosen, request.count());
        Mix.Source source = TrackSelection.anyValid(candidates, chosen) ? Mix.Source.LLM : Mix.Source.FALLBACK;
        Map<TrackId, String> reasons = new HashMap<>();
        for (Pick pick : picks) {
            if (!pick.reason().isEmpty() && tracks.stream().anyMatch(t -> t.id().equals(pick.id()))) {
                reasons.putIfAbsent(pick.id(), pick.reason());
            }
        }
        Mix mix = new Mix(tracks, source, reasons);
        rememberSuggested(request.playerId(), mix);
        return mix;
    }

    /** So skips of these tracks can be noticed; failing to remember must not fail the mix. */
    private void rememberSuggested(String playerId, Mix mix) {
        Map<String, String> reasons = new HashMap<>();
        mix.tracks().forEach(t -> mix.reasonFor(t.id()).ifPresent(r -> reasons.put(t.url(), r)));
        try {
            feedback.recordSuggested(playerId, mix.tracks(), reasons, clock.instant());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not remember suggestions for " + playerId, e);
        }
    }

    /** A failing history mustn't stop the music. */
    private Taste tasteOf(String playerId) {
        try {
            return taste.of(playerId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not work out the taste on " + playerId + "; mixing without it", e);
            return Taste.NONE;
        }
    }

    /** A failing mood store mustn't stop the music. */
    private Optional<Mood> currentMood(String playerId) {
        try {
            return moods.current(playerId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the mood of " + playerId + "; mixing without it", e);
            return Optional.empty();
        }
    }

    /**
     * Whether most of the end of the queue is music the listener chose rather than DJ's
     * suggestions since the mood was set: they've moved on, and the mood would only fight them.
     */
    private boolean listenerTookOver(String playerId, List<Track> queue, Mood mood) {
        List<Track> recent = queue.subList(Math.max(0, queue.size() - SEEDS_FROM_QUEUE), queue.size());
        try {
            long chosen = recent.stream().filter(t -> !feedback.wasSuggested(playerId, t.url(), mood.setAt())).count();
            return chosen * 2 > recent.size();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read suggestions for " + playerId + "; keeping the mood", e);
            return false;
        }
    }

    private void endMood(String playerId, Mood mood) {
        LOG.log(Level.INFO, "Ending mood \"{0}\" for {1}: the listener chose other music", mood.text(), playerId);
        try {
            moods.clear(playerId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not clear the mood of " + playerId, e);
        }
    }

    private List<Track> candidates(MixRequest request, MixBrief brief) {
        PlaybackContext context = brief.context();
        Mood.Style mood = brief.mood().map(Mood::style).orElse(Mood.Style.NONE);
        int poolSize = Math.max(settings.candidatePoolSize(), request.count());
        List<Track> all = library.allTracks();
        Map<TrackId, Track> byId = new HashMap<>();
        all.forEach(t -> byId.putIfAbsent(t.id(), t));

        List<Track> queue = context.queue();
        Map<TrackId, Track> queued = new HashMap<>();
        queue.forEach(t -> queued.putIfAbsent(t.id(), t));

        List<Track> seeds = List.of();
        if (!(brief.fresh() && brief.mood().isPresent())) {
            seeds = brief.fresh() ? timeOfDaySeeds.pick(request.playerId()) : List.of();
            if (seeds.isEmpty()) {
                seeds = request.seeds().stream()
                        .map(id -> byId.containsKey(id) ? byId.get(id) : queued.get(id))
                        .filter(Objects::nonNull)
                        .toList();
            }
            if (seeds.isEmpty()) {
                seeds = queue.subList(Math.max(0, queue.size() - SEEDS_FROM_QUEUE), queue.size());
            }
        }
        List<String> artists = new ArrayList<>(mood.artists());
        seeds.forEach(t -> artists.add(t.artist()));
        artists.addAll(brief.taste().artists().keySet());
        lookUpArtists(artists);
        discoverAroundStreamedArtists(queue, byId);

        boolean christmas = ChristmasSongs.wanted(context.localTime().toLocalDate(), mood,
                brief.mood().map(Mood::text).orElse(""), seeds);
        Predicate<Track> allowed = t -> christmas || !ChristmasSongs.isChristmasSong(t);

        Map<TrackId, Track> pool = new LinkedHashMap<>();
        // Artists aren't tagged "christmas", so asking for Christmas music wouldn't rank its songs.
        if (ChristmasSongs.asked(mood, brief.mood().map(Mood::text).orElse(""))) {
            List<Track> christmasSongs = new ArrayList<>(all);
            christmasSongs.addAll(discovered.allTracks());
            christmasSongs.removeIf(t -> !ChristmasSongs.isChristmasSong(t));
            scorer.rank(christmasSongs, seeds, queue, poolSize, Double.NEGATIVE_INFINITY, mood, brief.taste())
                    .forEach(t -> pool.putIfAbsent(t.id(), t));
        }
        for (Track track : neighbours(request.seeds(), poolSize)) {
            if (pool.size() < poolSize && !request.seeds().contains(track.id())
                    && !queued.containsKey(track.id()) && allowed.test(track)) {
                pool.putIfAbsent(track.id(), track);
            }
        }

        List<Track> fromLibrary = scorer.rank(all.stream().filter(allowed).toList(), seeds, queue, poolSize,
                Double.NEGATIVE_INFINITY, mood, brief.taste());
        List<Track> fromDiscovery = settings.discoveryShare() > 0
                ? scorer.rank(discovered.allTracks().stream().filter(allowed).toList(), seeds, queue, poolSize,
                        MixSettings.DISCOVERY_MIN_RELEVANCE, mood, brief.taste())
                : List.of();
        for (Track track : interleave(fromLibrary, fromDiscovery, settings.discoveryShare())) {
            if (pool.size() == poolSize) {
                break;
            }
            pool.putIfAbsent(track.id(), track);
        }
        return List.copyOf(pool.values());
    }

    /**
     * Merges two ranked lists so that about {@code share} of the result comes from
     * {@code discovered}; whichever list runs out first leaves the rest to the other.
     * With share 0.4 the pattern is library, library, discovered, library, discovered.
     */
    static List<Track> interleave(List<Track> library, List<Track> discovered, double share) {
        List<Track> merged = new ArrayList<>(library.size() + discovered.size());
        int l = 0;
        int d = 0;
        for (int i = 0; l < library.size() || d < discovered.size(); i++) {
            boolean wantDiscovered = Math.floor((i + 1) * share) > Math.floor(i * share);
            if ((wantDiscovered && d < discovered.size()) || l >= library.size()) {
                merged.add(discovered.get(d++));
            } else {
                merged.add(library.get(l++));
            }
        }
        return merged;
    }

    private void lookUpArtists(List<String> wanted) {
        List<String> artists = wanted.stream().filter(a -> !a.isBlank()).distinct().toList();
        if (enricher.isEmpty() || artists.isEmpty()) {
            return;
        }
        CompletableFuture<Void> lookup = CompletableFuture.runAsync(() -> enricher.get().ensure(artists), executor);
        try {
            lookup.get(settings.seedLookupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Not cancelled: let it finish and cache the profiles for the next mix.
            LOG.log(Level.INFO, "Artist lookup still running after {0,number,#} ms; mixing without it",
                    settings.seedLookupTimeout().toMillis());
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Artist lookup failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Fire and forget: results are stored for later mixes. */
    private void discoverAroundStreamedArtists(List<Track> queue, Map<TrackId, Track> library) {
        if (discoverer.isEmpty()) {
            return;
        }
        List<String> streamed = queue.stream()
                .filter(t -> !library.containsKey(t.id()))
                .map(Track::artist)
                .filter(a -> !a.isBlank())
                .distinct()
                .toList();
        if (!streamed.isEmpty()) {
            executor.execute(() -> {
                try {
                    discoverer.get().discoverAround(streamed);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Discovery around " + streamed + " failed", e);
                }
            });
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

    private List<Pick> askLlm(MixBrief brief, List<Track> candidates, int count) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        CompletableFuture<List<Pick>> answer =
                CompletableFuture.supplyAsync(() -> llm.choose(brief, candidates, count), executor);
        try {
            List<Pick> chosen = answer.get(settings.llmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return chosen == null ? List.of() : chosen;
        } catch (TimeoutException e) {
            answer.cancel(true);
            LOG.log(Level.WARNING, "LLM did not answer within {0,number,#} ms; using candidate order",
                    settings.llmTimeout().toMillis());
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "LLM failed; using candidate order", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }
}
