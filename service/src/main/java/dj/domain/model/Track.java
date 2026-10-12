package dj.domain.model;

import java.util.Objects;

/**
 * A playable track from the library.
 *
 * @param url the URL LMS plays, e.g. {@code file://...}, {@code spotify:track:...} or {@code tidal://...}
 */
public record Track(TrackId id, String url, String title, String artist, String album) {

    public Track {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
    }
}
