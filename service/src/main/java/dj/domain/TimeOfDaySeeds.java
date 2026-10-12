package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Play;
import dj.domain.model.SongTitles;
import dj.domain.model.Track;
import dj.domain.port.PlayHistory;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Picks seeds for starting from scratch: songs this player played around this time of day on
 * similar days (weekday or weekend), favouring recent weeks, so a new mix fits the mood people
 * listening on that player tend to be in at that hour.
 */
public final class TimeOfDaySeeds {

    static final int SEEDS = 5;
    static final Duration WINDOW = Duration.ofMinutes(90);
    static final Duration LOOKBACK = Duration.ofDays(56);
    static final double HALF_LIFE_DAYS = 14;
    /** Below this many plays on the same kind of day, any day counts. */
    static final int MIN_SAME_DAY_TYPE = 3;

    private final PlayHistory history;
    private final Clock clock;
    private final RandomGenerator random;

    public TimeOfDaySeeds(PlayHistory history, Clock clock, RandomGenerator random) {
        this.history = Objects.requireNonNull(history);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /** Up to {@link #SEEDS} distinct songs; empty if there's no history around this time yet. */
    public List<Track> pick(String playerId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        List<Play> plays = history.playsOn(playerId, now.toInstant().minus(LOOKBACK));

        List<Play> nearNow = plays.stream().filter(p -> minutesApart(at(p), now) <= WINDOW.toMinutes()).toList();
        List<Play> sameDayType = nearNow.stream().filter(p -> isWeekend(at(p)) == isWeekend(now)).toList();
        List<Play> candidates = sameDayType.size() >= MIN_SAME_DAY_TYPE ? sameDayType : nearNow;

        // One entry per song, keeping its best weight.
        Map<String, Track> songs = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Play play : candidates) {
            String song = ArtistNames.key(play.track().artist()) + "|" + SongTitles.key(play.track().title());
            double weight = weight(play, now);
            if (weight > weights.getOrDefault(song, 0.0)) {
                weights.put(song, weight);
                songs.put(song, play.track());
            }
        }
        return sample(songs, weights);
    }

    private ZonedDateTime at(Play play) {
        return play.startedAt().atZone(clock.getZone());
    }

    private static double weight(Play play, ZonedDateTime now) {
        double ageDays = Duration.between(play.startedAt(), now.toInstant()).toHours() / 24.0;
        double recency = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
        double closeness = 1 - 0.5 * minutesApart(play.startedAt().atZone(now.getZone()), now) / WINDOW.toMinutes();
        return recency * closeness;
    }

    /** Weighted sampling without replacement. */
    private List<Track> sample(Map<String, Track> songs, Map<String, Double> weights) {
        List<String> keys = new ArrayList<>(songs.keySet());
        List<Track> picked = new ArrayList<>();
        while (picked.size() < SEEDS && !keys.isEmpty()) {
            double total = keys.stream().mapToDouble(weights::get).sum();
            double r = random.nextDouble() * total;
            int i = 0;
            for (; i < keys.size() - 1; i++) {
                r -= weights.get(keys.get(i));
                if (r < 0) {
                    break;
                }
            }
            picked.add(songs.get(keys.remove(i)));
        }
        return picked;
    }

    /** Minutes between the two times of day, going round midnight if that's shorter. */
    static long minutesApart(ZonedDateTime a, ZonedDateTime b) {
        long diff = Math.abs(a.toLocalTime().toSecondOfDay() - b.toLocalTime().toSecondOfDay()) / 60;
        return Math.min(diff, 24 * 60 - diff);
    }

    private static boolean isWeekend(ZonedDateTime t) {
        return t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
