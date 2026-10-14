package dj.domain;

import dj.domain.model.Mood;
import dj.domain.port.MoodInterpreter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoodServiceTest {

    private static final String PLAYER = "aa:bb";
    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    private static final Mood.Style PROG = new Mood.Style(List.of("progressive rock"), List.of("Pink Floyd"), 1970, 1979);

    private final InMemoryMoodStore store = new InMemoryMoodStore();
    private final InMemoryProfileStore profiles = new InMemoryProfileStore()
            .with("Pink Floyd", List.of("progressive rock", "psychedelic"))
            .with("Camel", List.of("progressive rock"))
            .with("Gasolin'", List.of("danish", "rock"));
    private final List<String> asked = new ArrayList<>();
    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void setUnderstandsTheRequestAndStoresIt() {
        Mood mood = service(interpreting(PROG)).set(PLAYER, "  mellow   prog ");

        assertEquals(new Mood("mellow prog", PROG, NOW), mood);
        assertEquals(mood, service(interpreting(PROG)).current(PLAYER).orElseThrow());
    }

    @Test
    void theInterpreterChoosesFromTheMostCommonTags() {
        service(interpreting(PROG)).set(PLAYER, "prog");

        assertEquals(List.of("progressive rock", "danish", "psychedelic", "rock"), asked);
    }

    @Test
    void fallsBackToKeywordsWhenTheInterpreterFails() {
        MoodService service = service((request, tags) -> {
            throw new IllegalStateException("offline");
        });

        Mood mood = service.set(PLAYER, "Danish rock from the 70s");

        assertEquals(new Mood.Style(List.of("danish", "rock"), List.of(), 1970, 1979), mood.style());
    }

    @Test
    void fallsBackToKeywordsWhenTheInterpreterIsTooSlow() {
        MoodService service = service((request, tags) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PROG;
        });

        assertEquals(List.of("Pink Floyd"), service.set(PLAYER, "pink floyd").style().artists());
    }

    @Test
    void aRecentRequestIsNotInterpretedAgain() {
        service(interpreting(PROG)).set(PLAYER, "Mellow prog");
        asked.clear();

        Mood again = service(interpreting(Mood.Style.NONE)).set(PLAYER, "mellow PROG");

        assertEquals(PROG, again.style());
        assertTrue(asked.isEmpty());
    }

    @Test
    void aMoodExpiresAfterFourHours() {
        MoodService service = service(interpreting(PROG));
        service.set(PLAYER, "prog");

        clock.set(Clock.fixed(NOW.plus(MoodService.LASTS).minusSeconds(1), ZoneOffset.UTC));
        assertTrue(service(interpreting(PROG)).current(PLAYER).isPresent());
        clock.set(Clock.fixed(NOW.plus(MoodService.LASTS), ZoneOffset.UTC));
        assertTrue(service(interpreting(PROG)).current(PLAYER).isEmpty());
    }

    @Test
    void clearEndsTheMoodButKeepsItAmongTheRecentOnes() {
        MoodService service = service(interpreting(PROG));
        service.set(PLAYER, "prog");

        service.clear(PLAYER);

        assertTrue(service.current(PLAYER).isEmpty());
        assertEquals(List.of("prog"), service.recent(PLAYER).stream().map(Mood::text).toList());
    }

    @Test
    void forgettingARequestTakesItOffTheRecentOnesButLeavesTheMoodOn() {
        MoodService service = service(interpreting(PROG));
        service.set(PLAYER, "prog");
        service.set(PLAYER, "jazz");
        service.set(PLAYER, "Prog");

        service.forget(PLAYER, " PROG ");

        assertEquals(List.of("jazz"), service.recent(PLAYER).stream().map(Mood::text).toList());
        assertEquals("Prog", service.current(PLAYER).orElseThrow().text());
        assertThrows(IllegalArgumentException.class, () -> service.forget(PLAYER, " "));

        service.set(PLAYER, "prog");
        assertEquals(List.of("prog", "jazz"), service.recent(PLAYER).stream().map(Mood::text).toList());
    }

    @Test
    void moodsArePerPlayer() {
        service(interpreting(PROG)).set(PLAYER, "prog");

        assertTrue(service(interpreting(PROG)).current("other").isEmpty());
    }

    @Test
    void aBlankRequestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service(interpreting(PROG)).set(PLAYER, "   "));
    }

    @Test
    void recentMoodsAreNewestFirstWithoutRepeats() {
        MoodService service = service(interpreting(PROG));
        for (String text : List.of("a", "b", "A", "c", "d", "e", "f")) {
            service.set(PLAYER, text);
        }

        assertEquals(List.of("f", "e", "d", "c", "A"), service.recent(PLAYER).stream().map(Mood::text).toList());
    }

    private MoodInterpreter interpreting(Mood.Style style) {
        return (request, tags) -> {
            asked.addAll(tags);
            return style;
        };
    }

    private MoodService service(MoodInterpreter interpreter) {
        return new MoodService(store, interpreter, profiles,
                () -> List.of(track("1", "Pink Floyd"), track("2", "Gasolin'")),
                clock.get(), Executors.newVirtualThreadPerTaskExecutor(), Duration.ofMillis(300));
    }
}
