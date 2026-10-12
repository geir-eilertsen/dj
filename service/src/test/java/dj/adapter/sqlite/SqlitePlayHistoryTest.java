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
    void remembersSuggestionsPerPlayer() {
        SqlitePlayHistory history = new SqlitePlayHistory(dir.resolve("history.db"));
        history.recordSuggested("p1", List.of(play("p1", T, "Sommer").track()), java.util.Map.of(), T);

        assertEquals(true, history.wasSuggested("p1", "tidal://Sommer", T.minusSeconds(60)));
        assertEquals(false, history.wasSuggested("p2", "tidal://Sommer", T.minusSeconds(60)));
        assertEquals(false, history.wasSuggested("p1", "tidal://Sommer", T.plusSeconds(60)));
    }

    @Test
    void remembersTheLatestReasonForASuggestion() {
        SqlitePlayHistory history = new SqlitePlayHistory(dir.resolve("history.db"));
        Track sommer = play("p1", T, "Sommer").track();
        history.recordSuggested("p1", List.of(sommer), java.util.Map.of(sommer.url(), "Old reason"), T);
        history.recordSuggested("p1", List.of(sommer), java.util.Map.of(sommer.url(), "Danish summer classic"), T.plusSeconds(60));
        history.recordSuggested("p2", List.of(sommer), java.util.Map.of(), T.plusSeconds(120));

        assertEquals(Optional.of("Danish summer classic"), history.reasonFor("p1", sommer.url()));
        assertEquals(Optional.empty(), history.reasonFor("p2", sommer.url()), "suggested without a reason");
        assertEquals(Optional.empty(), history.reasonFor("p1", "tidal://other"));
    }

    @Test
    void upgradesAVersion2Database() throws Exception {
        Path db = dir.resolve("history.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db); var st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE play (player TEXT NOT NULL, started_at INTEGER NOT NULL, track_id TEXT NOT NULL,"
                    + " url TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, year INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE suggestion (player TEXT NOT NULL, url TEXT NOT NULL, suggested_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE skip (player TEXT NOT NULL, skipped_at INTEGER NOT NULL, url TEXT NOT NULL,"
                    + " title TEXT NOT NULL, artist TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO suggestion VALUES ('p1', 'tidal://Sommer', 1)");
            st.executeUpdate("PRAGMA user_version = 2");
        }

        SqlitePlayHistory history = new SqlitePlayHistory(db);

        assertEquals(true, history.wasSuggested("p1", "tidal://Sommer", Instant.EPOCH));
        assertEquals(Optional.empty(), history.reasonFor("p1", "tidal://Sommer"));
    }

    @Test
    void storesSkips() {
        SqlitePlayHistory history = new SqlitePlayHistory(dir.resolve("history.db"));
        Track sommer = play("p1", T, "Sommer").track();
        history.recordSkip(new dj.domain.model.Skip("p1", T, sommer));
        history.recordSkip(new dj.domain.model.Skip("p2", T.minusSeconds(3600), sommer));

        List<dj.domain.model.Skip> skips = new SqlitePlayHistory(dir.resolve("history.db")).skipsSince(T.minusSeconds(60));

        assertEquals(1, skips.size());
        assertEquals("Kim Larsen", skips.getFirst().track().artist());
        assertEquals("tidal://Sommer", skips.getFirst().track().url());
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
