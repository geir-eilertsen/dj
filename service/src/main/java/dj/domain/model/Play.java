package dj.domain.model;

import java.time.Instant;
import java.util.Objects;

/** A track that was played on a player, and when it started. */
public record Play(String playerId, Instant startedAt, Track track) {

    public Play {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(track, "track");
    }
}
