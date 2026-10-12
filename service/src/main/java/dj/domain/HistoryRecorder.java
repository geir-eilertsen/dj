package dj.domain;

import dj.domain.model.NowPlaying;
import dj.domain.model.Play;
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
 * Builds the listening log by looking at what each player is playing, every so often.
 *
 * <p>A track counts as played once it has been playing for {@link #MIN_PLAYED_SECONDS}, or half
 * its length if it is shorter. Each play is logged once, with the time it started.
 */
public final class HistoryRecorder {

    private static final Logger LOG = System.getLogger(HistoryRecorder.class.getName());

    static final double MIN_PLAYED_SECONDS = 30;

    private final PlayerActivityPort players;
    private final PlayHistory history;
    private final Clock clock;
    /** Player → start time of the play last logged, to log each play once. */
    private final Map<String, Instant> lastLogged = new HashMap<>();
    private final Map<String, String> lastLoggedUrl = new HashMap<>();

    public HistoryRecorder(PlayerActivityPort players, PlayHistory history, Clock clock) {
        this.players = Objects.requireNonNull(players);
        this.history = Objects.requireNonNull(history);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Looks at every player once. Never throws. */
    public synchronized void poll() {
        try {
            Instant now = clock.instant();
            for (NowPlaying np : players.nowPlaying()) {
                if (!np.playing() || np.track().url().isBlank() || !playedLongEnough(np)) {
                    continue;
                }
                Instant startedAt = now.minus(Duration.ofMillis((long) (np.elapsedSeconds() * 1000)));
                if (alreadyLogged(np.playerId(), np.track().url(), startedAt, np.durationSeconds())) {
                    continue;
                }
                history.record(new Play(np.playerId(), startedAt, np.track()));
                lastLogged.put(np.playerId(), startedAt);
                lastLoggedUrl.put(np.playerId(), np.track().url());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not record what is playing", e);
        }
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
