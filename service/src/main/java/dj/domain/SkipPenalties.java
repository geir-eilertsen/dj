package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Skip;
import dj.domain.model.SongTitles;
import dj.domain.model.Track;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much recent skips count against a song and its artist. Each skip counts fully when fresh
 * and half as much every {@link #HALF_LIFE_DAYS} days, so old skips fade away.
 */
public final class SkipPenalties {

    static final double HALF_LIFE_DAYS = 60;
    /** Skips older than this are ignored altogether. */
    public static final Duration MEMORY = Duration.ofDays(365);

    public static final SkipPenalties NONE = new SkipPenalties(Map.of(), Map.of());

    private final Map<String, Double> songs;
    private final Map<String, Double> artists;

    private SkipPenalties(Map<String, Double> songs, Map<String, Double> artists) {
        this.songs = songs;
        this.artists = artists;
    }

    public static SkipPenalties from(List<Skip> skips, Instant now) {
        Map<String, Double> songs = new HashMap<>();
        Map<String, Double> artists = new HashMap<>();
        for (Skip skip : skips) {
            double ageDays = Math.max(0, Duration.between(skip.at(), now).toHours() / 24.0);
            double weight = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
            songs.merge(songKey(skip.track()), weight, Double::sum);
            artists.merge(ArtistNames.groupKey(skip.track().artist()), weight, Double::sum);
        }
        return new SkipPenalties(songs, artists);
    }

    /** Score multiplier for this track: 1 if never skipped, smaller the more it (and its artist) were. */
    double factor(Track track, ScoringWeights weights) {
        double song = songs.getOrDefault(songKey(track), 0.0);
        double artist = artists.getOrDefault(ArtistNames.groupKey(track.artist()), 0.0);
        return Math.pow(weights.skippedSong(), song) * Math.pow(weights.skippedArtist(), artist);
    }

    private static String songKey(Track track) {
        return ArtistNames.key(track.artist()) + "|" + SongTitles.key(track.title());
    }
}
