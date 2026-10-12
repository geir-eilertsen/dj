package dj.adapter.sqlite;

import dj.domain.model.Play;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlitePlayHistoryTest {

    private static final Instant T = Instant.parse("2026-10-11T07:00:00Z");

    @TempDir
    Path dir;

    private static Play play(String player, Instant at, String title) {
        return new Play(player, at, new Track(TrackId.of("-1" + title.length()), "tidal://" + title, title, "Kim Larsen",
                "Midt Om Natten", 1983, 0));
    }

    @Test
    void returnsAPlayersPlaysSinceAGivenTimeOldestFirst() {
        SqlitePlayHistory history = new SqlitePlayHistory(dir.resolve("history.db"));
        history.record(play("p1", T.plusSeconds(600), "Sommer"));
        history.record(play("p1", T, "Frakken"));
        history.record(play("p1", T.minusSeconds(3600), "Old"));
        history.record(play("p2", T, "Other player"));

        assertEquals(List.of(play("p1", T, "Frakken"), play("p1", T.plusSeconds(600), "Sommer")),
                new SqlitePlayHistory(dir.resolve("history.db")).playsOn("p1", T));
    }

    @Test
    void knowsTheLastPlay() {
        SqlitePlayHistory history = new SqlitePlayHistory(dir.resolve("history.db"));
        history.record(play("p1", T, "Frakken"));
        history.record(play("p1", T.plusSeconds(600), "Sommer"));

        assertEquals(Optional.of(play("p1", T.plusSeconds(600), "Sommer")), history.lastPlayOn("p1"));
        assertEquals(Optional.empty(), history.lastPlayOn("p3"));
    }
}
