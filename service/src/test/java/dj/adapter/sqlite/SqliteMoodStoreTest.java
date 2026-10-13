package dj.adapter.sqlite;

import dj.domain.model.Mood;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMoodStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-13T18:00:00Z");
    private static final Mood.Style PROG = new Mood.Style(List.of("progressive rock", "art rock"), List.of("Pink Floyd"),
            1970, 1979);

    @TempDir
    Path dir;

    @Test
    void keepsMoodsAcrossRestarts() {
        Mood mood = new Mood("mellow prog", PROG, T0);
        new SqliteMoodStore(dir.resolve("mood.db")).save("p1", mood);

        assertEquals(mood, new SqliteMoodStore(dir.resolve("mood.db")).latest("p1").orElseThrow());
    }

    @Test
    void anEmptyStyleSurvivesTheRoundTrip() {
        SqliteMoodStore store = new SqliteMoodStore(dir.resolve("mood.db"));
        store.save("p1", new Mood("whatever", Mood.Style.NONE, T0));

        assertEquals(Mood.Style.NONE, store.latest("p1").orElseThrow().style());
    }

    @Test
    void clearingEndsTheLatestMoodUntilANewOneIsSet() {
        SqliteMoodStore store = new SqliteMoodStore(dir.resolve("mood.db"));
        store.save("p1", new Mood("a", PROG, T0));
        store.save("p2", new Mood("b", PROG, T0));

        store.clear("p1", T0.plusSeconds(60));
        assertTrue(store.latest("p1").isEmpty());
        assertEquals("b", store.latest("p2").orElseThrow().text());

        store.save("p1", new Mood("c", PROG, T0.plusSeconds(120)));
        assertEquals("c", store.latest("p1").orElseThrow().text());
    }

    @Test
    void recentMoodsAreNewestFirstOncePerText() {
        SqliteMoodStore store = new SqliteMoodStore(dir.resolve("mood.db"));
        List<String> texts = List.of("a", "b", "A", "c");
        for (int i = 0; i < texts.size(); i++) {
            store.save("p1", new Mood(texts.get(i), PROG, T0.plusSeconds(i)));
        }

        assertEquals(List.of("c", "A", "b"), store.recent("p1", 5).stream().map(Mood::text).toList());
        assertEquals(List.of("c", "A"), store.recent("p1", 2).stream().map(Mood::text).toList());
    }
}
