package dj.domain;

import dj.domain.model.NowPlaying;
import dj.domain.model.Play;
import dj.domain.model.Skip;
import dj.domain.model.Track;
import dj.domain.port.FeedbackStore;
import dj.domain.port.PlayHistory;
import dj.domain.port.PlayerActivityPort;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Watches what each player is playing, every few seconds, to build the listening log and
 * notice skipped suggestions.
 *
 * <p>A track counts as played once it has been playing for {@link #MIN_PLAYED_SECONDS}, or half
 * its length if it is shorter. Each play is logged once, with the time it started.
 *
 * <p>A track DJ suggested counts as skipped when the player moves forward in the queue while
 * it still had more than {@link #SKIP_MARGIN_SECONDS} to go, or jumps past it without it being
 * seen at all. Going back, pausing, stopping or replacing the queue is not a skip.
 */
public final class HistoryRecorder {

    private static final Logger LOG = System.getLogger(HistoryRecorder.class.getName());

    static final double MIN_PLAYED_SECONDS = 30;
    static final double SKIP_MARGIN_SECONDS = 15;
    /** Suggestions older than this don't count when deciding whether a skip was of a suggestion. */
    static final Duration SUGGESTION_MEMORY = Duration.ofDays(2);

    private record Observation(NowPlaying np, Instant at) {
    }

    private final PlayerActivityPort players;
    private final PlayHistory history;
    private final FeedbackStore feedback;
    private final Clock clock;
    /** Player → start time and URL of the play last logged, to log each play once. */
    private final Map<String, Instant> lastLogged = new HashMap<>();
    private final Map<String, String> lastLoggedUrl = new HashMap<>();
    private final Map<String, Observation> lastSeen = new HashMap<>();

    public HistoryRecorder(PlayerActivityPort players, PlayHistory history, FeedbackStore feedback, Clock clock) {
        this.players = Objects.requireNonNull(players);
        this.history = Objects.requireNonNull(history);
        this.feedback = Objects.requireNonNull(feedback);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Looks at every player once. Never throws. */
    public synchronized void poll() {
        try {
            Instant now = clock.instant();
            for (NowPlaying np : players.nowPlaying()) {
                noticeSkips(np, now);
                lastSeen.put(np.playerId(), new Observation(np, now));
                logPlay(np, now);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not record what is playing", e);
        }
    }

    private void noticeSkips(NowPlaying current, Instant now) {
        Observation previous = lastSeen.get(current.playerId());
        if (previous == null || !previous.np().playing() || current.queueIndex() <= previous.np().queueIndex()) {
            return;
        }
        NowPlaying before = previous.np();
        double played = before.elapsedSeconds() + Duration.between(previous.at(), now).toMillis() / 1000.0;
        if (before.durationSeconds() > 0 && before.durationSeconds() - played > SKIP_MARGIN_SECONDS) {
            skipped(current.playerId(), before.track(), now);
        }
        // Tracks jumped over between two looks were never played.
        if (current.queueIndex() > before.queueIndex() + 1) {
            for (Track passed : players.queueSlice(current.playerId(), before.queueIndex() + 1, current.queueIndex())) {
                skipped(current.playerId(), passed, now);
            }
        }
    }

    private void skipped(String playerId, Track track, Instant now) {
        if (feedback.wasSuggested(playerId, track.url(), now.minus(SUGGESTION_MEMORY))) {
            feedback.recordSkip(new Skip(playerId, now, track));
            LOG.log(Level.INFO, "Skipped on {0}: {1} - {2}", playerId, track.artist(), track.title());
        }
    }

    private void logPlay(NowPlaying np, Instant now) {
        if (!np.playing() || np.track().url().isBlank() || !playedLongEnough(np)) {
            return;
        }
        Instant startedAt = now.minus(Duration.ofMillis((long) (np.elapsedSeconds() * 1000)));
        if (alreadyLogged(np.playerId(), np.track().url(), startedAt, np.durationSeconds())) {
            return;
        }
        history.record(new Play(np.playerId(), startedAt, np.track()));
        lastLogged.put(np.playerId(), startedAt);
        lastLoggedUrl.put(np.playerId(), np.track().url());
    }

    private static boolean playedLongEnough(NowPlaying np) {
        double needed = np.durationSeconds() > 0 ? Math.min(MIN_PLAYED_SECONDS, np.durationSeconds() / 2) : MIN_PLAYED_SECONDS;
        return np.elapsedSeconds() >= needed;
    }

    /**
     * Same track whose logged start is within the track's length of this one: the same play
     * (start times drift a little between polls). Also checks the store, so a restart doesn't
     * log the play again.
     */
    private boolean alreadyLogged(String playerId, String url, Instant startedAt, double durationSeconds) {
        if (!lastLogged.containsKey(playerId)) {
            history.lastPlayOn(playerId).ifPresent(p -> {
                lastLogged.put(playerId, p.startedAt());
                lastLoggedUrl.put(playerId, p.track().url());
            });
        }
        Instant last = lastLogged.get(playerId);
        if (last == null || !url.equals(lastLoggedUrl.get(playerId))) {
            return false;
        }
        Duration window = Duration.ofSeconds((long) Math.max(durationSeconds, 60));
        return Duration.between(last, startedAt).abs().compareTo(window) < 0;
    }
}
