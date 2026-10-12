package dj.domain.model;

import java.time.Instant;
import java.util.Objects;

/** A suggested track the listener skipped. */
public record Skip(String playerId, Instant at, Track track) {

    public Skip {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(track, "track");
    }
}
