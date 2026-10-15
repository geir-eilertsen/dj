package dj.domain;

import dj.domain.model.Play;
import dj.domain.model.Taste;
import dj.domain.model.Track;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ListenerTasteTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final String PLAYER = "p1";

    private final InMemoryPlayHistory history = new InMemoryPlayHistory();
    private List<Track> library = List.of();

    @Test
    void favoursArtistsPlayedMostAndLatelyOnThePlayer() {
        play("Pixies", 40);
        play("Pixies", 40);
        play("Gazpacho", 1);
        play("Saga", 1);
        play("Saga", 2);
        play("Abba", 200);
        history.record(new Play("other", NOW, track("9", "Opeth")));

        Taste taste = taste();

        assertEquals(List.of("Saga", "Gazpacho", "Pixies"), List.copyOf(taste.artists().keySet()));
        assertEquals(1.0, taste.artists().get("Saga"));
    }

    @Test
    void withLittleHistoryTheLibrarysPlayCountsDecide() {
        play("Pixies", 1);
        library = List.of(track("1", "Saga", 1980, 3), track("2", "Saga", 1981, 4), track("3", "Pixies", 1988, 5),
                track("4", "", 0, 50), track("5", "Abba", 1976, 0));

        assertEquals(List.of("Saga", "Pixies"), List.copyOf(taste().artists().keySet()));
    }

    private void play(String artist, int daysAgo) {
        history.record(new Play(PLAYER, NOW.minus(Duration.ofDays(daysAgo)), track(artist, artist)));
    }

    private Taste taste() {
        return new ListenerTaste(history, () -> library, Clock.fixed(NOW, ZoneOffset.UTC)).of(PLAYER);
    }
}
