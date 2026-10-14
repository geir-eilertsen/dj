package dj.domain;

import dj.domain.model.ArtistProfile;
import dj.domain.model.Mood;
import dj.domain.model.Track;
import dj.domain.port.ArtistProfileStore;
import dj.domain.port.LibraryPort;
import dj.domain.port.MoodInterpreter;
import dj.domain.port.MoodStore;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The listener's requests ("mellow prog for Sunday morning"), one per player.
 *
 * <p>A request is understood once, when it is set: by the {@link MoodInterpreter} (an LLM)
 * under a timeout, else by {@link MoodKeywords}. Setting a recent request again reuses how it
 * was understood then. A mood lasts {@link #LASTS} or until cleared.
 */
public final class MoodService {

    private static final Logger LOG = System.getLogger(MoodService.class.getName());

    public static final Duration LASTS = Duration.ofHours(4);
    static final int RECENT = 5;
    static final int MAX_LENGTH = 200;
    /** How many of the most common tags the interpreter chooses from. */
    static final int KNOWN_TAGS = 150;
    /** Tags per artist that count towards the vocabulary; the rest are mostly noise. */
    static final int TAGS_PER_ARTIST = 8;

    private final MoodStore store;
    private final MoodInterpreter interpreter;
    private final ArtistProfileStore profiles;
    private final LibraryPort library;
    private final Clock clock;
    private final Executor executor;
    private final Duration interpretTimeout;

    public MoodService(MoodStore store, MoodInterpreter interpreter, ArtistProfileStore profiles, LibraryPort library,
                       Clock clock, Executor executor, Duration interpretTimeout) {
        this.store = Objects.requireNonNull(store);
        this.interpreter = Objects.requireNonNull(interpreter);
        this.profiles = Objects.requireNonNull(profiles);
        this.library = Objects.requireNonNull(library);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.interpretTimeout = Objects.requireNonNull(interpretTimeout);
    }

    /**
     * Sets the player's mood.
     *
     * @throws IllegalArgumentException if the request is blank
     */
    public Mood set(String playerId, String request) {
        String text = request == null ? "" : request.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("the mood is empty");
        }
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        String wanted = text;
        Mood.Style style = store.recent(playerId, RECENT).stream()
                .filter(m -> m.text().equalsIgnoreCase(wanted) && !m.style().isEmpty())
                .map(Mood::style)
                .findFirst()
                .orElseGet(() -> understand(wanted));
        Mood mood = new Mood(text, style, clock.instant());
        store.save(playerId, mood);
        LOG.log(Level.INFO, "Mood for {0}: \"{1}\" = {2}", playerId, text, style);
        return mood;
    }

    /** The player's mood, unless it was cleared or has expired. */
    public Optional<Mood> current(String playerId) {
        Instant now = clock.instant();
        return store.latest(playerId).filter(m -> m.setAt().plus(LASTS).isAfter(now));
    }

    public void clear(String playerId) {
        store.clear(playerId, clock.instant());
    }

    /**
     * Takes a request off the player's recent moods. If it's the current mood, it stays on.
     *
     * @throws IllegalArgumentException if the request is blank
     */
    public void forget(String playerId, String request) {
        String text = request == null ? "" : request.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("the mood is empty");
        }
        store.forget(playerId, text, clock.instant());
    }

    /** Recent moods for one-tap reuse, newest first. */
    public List<Mood> recent(String playerId) {
        return store.recent(playerId, RECENT);
    }

    private Mood.Style understand(String text) {
        List<String> knownTags = knownTags();
        Mood.Style style = interpret(text, knownTags);
        if (!style.isEmpty()) {
            return style;
        }
        List<String> artists;
        try {
            artists = library.allTracks().stream().map(Track::artist).distinct().toList();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Library unavailable; matching the mood on tags only", e);
            artists = List.of();
        }
        return MoodKeywords.interpret(text, knownTags, artists);
    }

    private Mood.Style interpret(String text, List<String> knownTags) {
        CompletableFuture<Mood.Style> answer =
                CompletableFuture.supplyAsync(() -> interpreter.interpret(text, knownTags), executor);
        try {
            Mood.Style style = answer.get(interpretTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return style == null ? Mood.Style.NONE : style;
        } catch (TimeoutException e) {
            answer.cancel(true);
            LOG.log(Level.WARNING, "Mood not understood within {0,number,#} ms; matching keywords",
                    interpretTimeout.toMillis());
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Could not understand the mood; matching keywords", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Mood.Style.NONE;
    }

    /** The most common tags among the artists' top tags. */
    List<String> knownTags() {
        Map<String, Integer> counts = new HashMap<>();
        for (ArtistProfile profile : profiles.all()) {
            profile.tags().stream()
                    .limit(TAGS_PER_ARTIST)
                    .forEach(t -> counts.merge(t.name().strip().toLowerCase(Locale.ROOT), 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(KNOWN_TAGS)
                .map(Map.Entry::getKey)
                .toList();
    }
}
