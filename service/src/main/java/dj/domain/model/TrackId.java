package dj.domain.model;

import java.util.Objects;

/**
 * Identifier of a track in the LMS library.
 *
 * <p>Kept as an opaque string so the domain doesn't depend on how LMS numbers its tracks.
 */
public record TrackId(String value) {

    public TrackId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Track id must not be blank");
        }
    }

    public static TrackId of(String value) {
        return new TrackId(value.strip());
    }

    @Override
    public String toString() {
        return value;
    }
}
