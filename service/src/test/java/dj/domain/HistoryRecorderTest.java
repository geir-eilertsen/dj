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
    private final InMemoryFeedbackStore feedback = new InMemoryFeedbackStore();
    private List<NowPlaying> nowPlaying = List.of();
    private List<Track> queue = List.of();
    private final dj.domain.port.PlayerActivityPort players = new dj.domain.port.PlayerActivityPort() {
        @Override
        public List<NowPlaying> nowPlaying() {
            return nowPlaying;
        }

        @Override
        public List<Track> queueSlice(String playerId, int from, int to) {
            return queue.subList(from, to);
        }
    };
    private final HistoryRecorder recorder = new HistoryRecorder(players, history, feedback, clock);

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
        nowPlaying = List.of(new NowPlaying("p1", false, song, 0, 100, 240), new NowPlaying("p2", true, next, 0, 21, 40));
        recorder.poll();

        assertEquals(List.of("p2"), history.plays.stream().map(Play::playerId).toList());
    }

    @Test
    void doesNotLogAPlayAgainAfterARestart() {
        playing("p1", song, 40, 240);
        recorder.poll();
        HistoryRecorder restarted = new HistoryRecorder(players, history, feedback, clock);
        clock.advance(30);
        playing("p1", song, 70, 240);

        restarted.poll();

        assertEquals(1, history.plays.size());
    }

    @Test
    void noticesASkippedSuggestion() {
        feedback.recordSuggested("p1", List.of(song), T0);
        playing("p1", song, 3, 20, 240);
        recorder.poll();
        clock.advance(10);
        playing("p1", next, 4, 2, 200);

        recorder.poll();

        assertEquals(List.of(song), feedback.skips.stream().map(dj.domain.model.Skip::track).toList());
    }

    @Test
    void aSuggestionPlayedToTheEndIsNotASkip() {
        feedback.recordSuggested("p1", List.of(song), T0);
        playing("p1", song, 3, 225, 240);
        recorder.poll();
        clock.advance(10);
        playing("p1", next, 4, 1, 200);

        recorder.poll();

        assertTrue(feedback.skips.isEmpty());
    }

    @Test
    void onlySuggestionsCountAsSkips() {
        playing("p1", song, 3, 20, 240);
        recorder.poll();
        clock.advance(10);
        playing("p1", next, 4, 2, 200);

        recorder.poll();

        assertTrue(feedback.skips.isEmpty(), "the listener chose that track themselves");
    }

    @Test
    void tracksJumpedOverBetweenLooksAreSkipped() {
        Track jumpedOver = track("9", "TV-2");
        feedback.recordSuggested("p1", List.of(jumpedOver), T0);
        queue = List.of(track("a"), track("b"), track("c"), song, jumpedOver, next);
        playing("p1", song, 3, 225, 240);
        recorder.poll();
        clock.advance(10);
        playing("p1", next, 5, 1, 200);

        recorder.poll();

        assertEquals(List.of(jumpedOver), feedback.skips.stream().map(dj.domain.model.Skip::track).toList());
    }

    @Test
    void goingBackOrStartingANewQueueIsNotASkip() {
        feedback.recordSuggested("p1", List.of(song, next), T0);
        playing("p1", song, 3, 20, 240);
        recorder.poll();
        clock.advance(10);
        playing("p1", next, 2, 2, 200);
        recorder.poll();
        clock.advance(10);
        playing("p1", song, 0, 2, 240);

        recorder.poll();

        assertTrue(feedback.skips.isEmpty());
    }

    @Test
    void keepsGoingWhenLmsCannotBeAsked() {
        HistoryRecorder failing = new HistoryRecorder(new dj.domain.port.PlayerActivityPort() {
            @Override
            public List<NowPlaying> nowPlaying() {
                throw new IllegalStateException("LMS down");
            }

            @Override
            public List<Track> queueSlice(String playerId, int from, int to) {
                throw new IllegalStateException("LMS down");
            }
        }, history, feedback, clock);

        failing.poll();

        assertTrue(history.plays.isEmpty());
    }

    private void playing(String player, Track track, double elapsed, double duration) {
        playing(player, track, 0, elapsed, duration);
    }

    private void playing(String player, Track track, int index, double elapsed, double duration) {
        nowPlaying = new ArrayList<>(List.of(new NowPlaying(player, true, track, index, elapsed, duration)));
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
