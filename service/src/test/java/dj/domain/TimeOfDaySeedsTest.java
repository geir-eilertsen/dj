package dj.domain;

import dj.domain.model.Play;
import dj.domain.model.Track;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeOfDaySeedsTest {

    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");
    /** A Sunday morning. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 11, 9, 0, 0, 0, OSLO);

    private final InMemoryPlayHistory history = new InMemoryPlayHistory();

    @Test
    void picksSongsPlayedAroundThisTimeOnThisPlayer() {
        played("p1", "sunday", NOW.minusWeeks(1).withHour(9).withMinute(30));
        played("p1", "sunday", NOW.minusWeeks(2).withHour(8));
        played("p1", "evening", NOW.minusWeeks(1).withHour(21));
        played("p2", "other player", NOW.minusWeeks(1));

        assertEquals(Set.of("sunday"), artists(seeds().pick("p1")));
    }

    @Test
    void prefersTheSameKindOfDay() {
        for (int week = 1; week <= 3; week++) {
            played("p1", "weekend " + week, NOW.minusWeeks(week));
        }
        played("p1", "monday", NOW.plusDays(1).minusWeeks(1));

        assertTrue(artists(seeds().pick("p1")).stream().allMatch(a -> a.startsWith("weekend")));
    }

    @Test
    void usesAnyDayWhenTheSameKindOfDayHasTooLittleHistory() {
        played("p1", "monday", NOW.plusDays(1).minusWeeks(1));

        assertEquals(Set.of("monday"), artists(seeds().pick("p1")));
    }

    @Test
    void wrapsAroundMidnight() {
        ZonedDateTime lateNight = NOW.withHour(23).withMinute(45);
        played("p1", "after midnight", lateNight.minusWeeks(1).plusMinutes(60));

        TimeOfDaySeeds seeds = new TimeOfDaySeeds(history, Clock.fixed(lateNight.toInstant(), OSLO), new Random(1));

        assertEquals(Set.of("after midnight"), artists(seeds.pick("p1")));
    }

    @Test
    void ignoresHistoryOlderThanTheLookback() {
        played("p1", "long ago", NOW.minusWeeks(10));

        assertTrue(seeds().pick("p1").isEmpty());
    }

    @Test
    void picksAtMostFiveDistinctSongs() {
        for (int i = 0; i < 20; i++) {
            played("p1", "artist " + i, NOW.minusWeeks(1).plusMinutes(i));
            played("p1", "artist " + i, NOW.minusWeeks(2).plusMinutes(i));
        }

        List<Track> picked = seeds().pick("p1");

        assertEquals(TimeOfDaySeeds.SEEDS, picked.size());
        assertEquals(TimeOfDaySeeds.SEEDS, artists(picked).size());
    }

    @Test
    void favoursRecentWeeks() {
        played("p1", "last week", NOW.minusWeeks(1));
        played("p1", "seven weeks ago", NOW.minusWeeks(7));
        int recentFirst = 0;
        for (int seed = 0; seed < 200; seed++) {
            TimeOfDaySeeds seeds = new TimeOfDaySeeds(history, Clock.fixed(NOW.toInstant(), OSLO), new Random(seed));
            if (seeds.pick("p1").getFirst().artist().equals("last week")) {
                recentFirst++;
            }
        }
        assertTrue(recentFirst > 150, "recent week picked first " + recentFirst + " of 200 times");
    }

    private TimeOfDaySeeds seeds() {
        return new TimeOfDaySeeds(history, Clock.fixed(NOW.toInstant(), OSLO), new Random(42));
    }

    private void played(String player, String artist, ZonedDateTime at) {
        history.record(new Play(player, at.toInstant(), track(String.valueOf(history.plays.size() + 1), artist)));
    }

    private static Set<String> artists(List<Track> tracks) {
        return tracks.stream().map(Track::artist).collect(Collectors.toSet());
    }
}
