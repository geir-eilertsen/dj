package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Play;
import dj.domain.model.Taste;
import dj.domain.model.Track;
import dj.domain.port.LibraryPort;
import dj.domain.port.PlayHistory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Works out which artists a listener likes: those played most on this player lately, or, with
 * too little history there, those with the most plays in the library. A mood started from
 * scratch has no seeds, so this is what keeps it close to the listener's own music.
 */
public final class ListenerTaste {

    static final int ARTISTS = 10;
    static final Duration LOOKBACK = Duration.ofDays(90);
    static final double HALF_LIFE_DAYS = 30;
    /** Below this many artists played on the player, the library's play counts decide. */
    static final int MIN_ARTISTS = 3;

    private final PlayHistory history;
    private final LibraryPort library;
    private final Clock clock;

    public ListenerTaste(PlayHistory history, LibraryPort library, Clock clock) {
        this.history = Objects.requireNonNull(history);
        this.library = Objects.requireNonNull(library);
        this.clock = Objects.requireNonNull(clock);
    }

    public Taste of(String playerId) {
        Instant now = clock.instant();
        Map<String, String> names = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (Play play : history.playsOn(playerId, now.minus(LOOKBACK))) {
            double ageDays = Duration.between(play.startedAt(), now).toHours() / 24.0;
            add(play.track().artist(), Math.pow(0.5, ageDays / HALF_LIFE_DAYS), names, scores);
        }
        if (scores.size() < MIN_ARTISTS) {
            names.clear();
            scores.clear();
            for (Track track : library.allTracks()) {
                add(track.artist(), track.playCount(), names, scores);
            }
        }
        List<Map.Entry<String, Double>> best = scores.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(ARTISTS)
                .toList();
        Map<String, Double> artists = new LinkedHashMap<>();
        best.forEach(e -> artists.put(names.get(e.getKey()), e.getValue() / best.getFirst().getValue()));
        return new Taste(artists);
    }

    private static void add(String artist, double score, Map<String, String> names, Map<String, Double> scores) {
        String key = ArtistNames.key(artist);
        if (key.isEmpty()) {
            return;
        }
        names.putIfAbsent(key, artist);
        scores.merge(key, score, Double::sum);
    }
}
