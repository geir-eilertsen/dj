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

    @Test
    void forgottenMoodsLeaveTheRecentOnesUntilSetAgain() {
        SqliteMoodStore store = new SqliteMoodStore(dir.resolve("mood.db"));
        store.save("p1", new Mood("Øl og prog", PROG, T0));
        store.save("p1", new Mood("jazz", PROG, T0.plusSeconds(1)));
        store.save("p1", new Mood("øl og prog", PROG, T0.plusSeconds(2)));
        store.save("p2", new Mood("øl og prog", PROG, T0));

        store.forget("p1", "ØL OG PROG", T0.plusSeconds(3));

        SqliteMoodStore reopened = new SqliteMoodStore(dir.resolve("mood.db"));
        assertEquals(List.of("jazz"), reopened.recent("p1", 5).stream().map(Mood::text).toList());
        assertEquals("øl og prog", reopened.latest("p1").orElseThrow().text());
        assertEquals(1, reopened.recent("p2", 5).size());

        reopened.save("p1", new Mood("øl og prog", PROG, T0.plusSeconds(4)));
        assertEquals(List.of("øl og prog", "jazz"), reopened.recent("p1", 5).stream().map(Mood::text).toList());
    }

    @Test
    void addsForgettingToAVersion1Database() throws Exception {
        java.nio.file.Path db = dir.resolve("mood.db");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE mood (player TEXT NOT NULL, text TEXT NOT NULL, tags TEXT NOT NULL, "
                    + "artists TEXT NOT NULL, from_year INTEGER NOT NULL, to_year INTEGER NOT NULL, "
                    + "set_at INTEGER NOT NULL, cleared_at INTEGER)");
            st.executeUpdate("INSERT INTO mood VALUES ('p1', 'prog', '', '', 0, 0, 1, NULL)");
            st.executeUpdate("PRAGMA user_version = 1");
        }
        SqliteMoodStore store = new SqliteMoodStore(db);
        assertEquals(1, store.recent("p1", 5).size());
        store.forget("p1", "prog", T0);
        assertEquals(List.of(), store.recent("p1", 5));
    }
}
