package dj.domain;

import dj.domain.model.Mood;
import dj.domain.model.Photo;
import dj.domain.model.PhotoReading;
import dj.domain.port.PhotoReader;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoMoodsTest {

    private static final String PLAYER = "aa:bb";
    // Thursday 07:36 in Oslo
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T05:36:00Z"), ZoneId.of("Europe/Oslo"));
    private static final Photo PHOTO = new Photo(new byte[]{1, 2, 3}, "image/jpeg");

    private final InMemoryMoodStore store = new InMemoryMoodStore();
    private final List<LocalDateTime> askedAt = new ArrayList<>();
    private final List<Integer> photoCounts = new ArrayList<>();

    @Test
    void setsTheMoodThePhotosShowAtTheListenersLocalTime() {
        PhotoMoods.Result result = photoMoods(reading(" gentle soft rock for a quiet morning ", "Morning light."))
                .fromPhotos(PLAYER, List.of(PHOTO, PHOTO));

        assertEquals("gentle soft rock for a quiet morning", result.mood().text());
        assertEquals("Morning light.", result.why());
        assertEquals(List.of(LocalDateTime.of(2026, 10, 15, 7, 36)), askedAt);
        assertEquals(List.of(2), photoCounts);
        assertEquals("gentle soft rock for a quiet morning", store.latest(PLAYER).orElseThrow().text());
    }

    @Test
    void takesOneToFourPhotos() {
        PhotoMoods photoMoods = photoMoods(reading("calm", ""));

        assertThrows(IllegalArgumentException.class, () -> photoMoods.fromPhotos(PLAYER, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> photoMoods.fromPhotos(PLAYER, Collections.nCopies(PhotoMoods.MAX_PHOTOS + 1, PHOTO)));
        assertTrue(photoCounts.isEmpty());
    }

    @Test
    void anEmptyReadingSetsNoMood() {
        assertThrows(IllegalStateException.class,
                () -> photoMoods(reading(" ", "nothing to see")).fromPhotos(PLAYER, List.of(PHOTO)));
        assertTrue(store.latest(PLAYER).isEmpty());
    }

    @Test
    void photosMustBePictures() {
        assertThrows(IllegalArgumentException.class, () -> new Photo(new byte[]{1}, "text/html"));
        assertThrows(IllegalArgumentException.class, () -> new Photo(new byte[0], "image/png"));
    }

    private PhotoReader reading(String mood, String why) {
        return (photos, localTime) -> {
            photoCounts.add(photos.size());
            askedAt.add(localTime);
            return new PhotoReading(mood, why);
        };
    }

    private PhotoMoods photoMoods(PhotoReader reader) {
        MoodService moods = new MoodService(store, (request, tags) -> Mood.Style.NONE, new InMemoryProfileStore(),
                List::of, CLOCK, Executors.newVirtualThreadPerTaskExecutor(), Duration.ofSeconds(1));
        return new PhotoMoods(reader, moods, CLOCK);
    }
}
