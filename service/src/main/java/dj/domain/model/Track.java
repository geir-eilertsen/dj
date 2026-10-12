package dj.domain.model;

import java.util.Objects;

/**
 * A playable track from the library.
 *
 * @param url       the URL LMS plays, e.g. {@code file://...}, {@code spotify:track:...} or {@code tidal://...}
 * @param year      release year, or 0 if unknown
 * @param playCount how often the track has been played in LMS
 */
public record Track(TrackId id, String url, String title, String artist, String album, int year, int playCount) {

    public Track {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        title = Objects.requireNonNullElse(title, "");
        artist = Objects.requireNonNullElse(artist, "");
        album = Objects.requireNonNullElse(album, "");
    }
}
