package dj.domain;

import dj.domain.model.NowPlaying;
import dj.domain.model.Play;
import dj.domain.model.Track;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryRecorderTest {

    private static final Instant T0 = Instant.parse("2026-10-11T08:00:00Z");

    private final InMemoryPlayHistory history = new InMemoryPlayHistory();
    private final MutableClock clock = new MutableClock();
    private List<NowPlaying> nowPlaying = List.of();
    private final HistoryRecorder recorder = new HistoryRecorder(() -> nowPlaying, history, clock);

    private final Track song = track("1", "Kim Larsen");
    private final Track next = track("2", "Gasolin'");

    @Test
    void logsATrackOnceItHasPlayedLongEnoughWithItsStartTime() {
        playing("p1", song, 10, 240);
        recorder.poll();
        assertTrue(history.plays.isEmpty(), "10 seconds is too short");

        clock.advance(30);
        playing("p1", song, 40, 240);
        recorder.poll();

        assertEquals(List.of(new Play("p1", T0.plusSeconds(30 - 40), song)), history.plays);
    }

    @Test
    void logsEachPlayOnlyOnce() {
        playing("p1", song, 40, 240);
        recorder.poll();
        clock.advance(30);
        playing("p1", song, 70.4, 240);
        recorder.poll();

        assertEquals(1, history.plays.size());
    }

    @Test
    void logsTheNextTrackAndRepeatsOfTheSameTrack() {
        playing("p1", song, 40, 240);
        recorder.poll();
        clock.advance(240);
        playing("p1", next, 30, 200);
        recorder.poll();
        clock.advance(400);
        playing("p1", song, 35, 240);
        recorder.poll();

        assertEquals(List.of("1", "2", "1"), history.plays.stream().map(p -> p.track().id().value()).toList());
    }

    @Test
    void ignoresPausedPlayersAndCountsShortTracksAtHalfway() {
        nowPlaying = List.of(new NowPlaying("p1", false, song, 100, 240), new NowPlaying("p2", true, next, 21, 40));
        recorder.poll();

        assertEquals(List.of("p2"), history.plays.stream().map(Play::playerId).toList());
    }

    @Test
    void doesNotLogAPlayAgainAfterARestart() {
        playing("p1", song, 40, 240);
        recorder.poll();
        HistoryRecorder restarted = new HistoryRecorder(() -> nowPlaying, history, clock);
        clock.advance(30);
        playing("p1", song, 70, 240);

        restarted.poll();

        assertEquals(1, history.plays.size());
    }

    @Test
    void keepsGoingWhenLmsCannotBeAsked() {
        HistoryRecorder failing = new HistoryRecorder(() -> {
            throw new IllegalStateException("LMS down");
        }, history, clock);

        failing.poll();

        assertTrue(history.plays.isEmpty());
    }

    private void playing(String player, Track track, double elapsed, double duration) {
        nowPlaying = new ArrayList<>(List.of(new NowPlaying(player, true, track, elapsed, duration)));
    }

    private static final class MutableClock extends Clock {
        private Instant now = T0;

        void advance(long seconds) {
            now = now.plus(Duration.ofSeconds(seconds));
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
