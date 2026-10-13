package dj.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * A request for tracks for a player.
 *
 * @param playerId LMS player id (MAC address)
 * @param seeds    the tracks at the end of the current queue, oldest first
 * @param count    how many tracks to return
 * @param fresh    the tracks start a new queue rather than follow the current one
 */
public record MixRequest(String playerId, List<TrackId> seeds, int count, boolean fresh) {

    public static final int MAX_COUNT = 50;

    public MixRequest {
        Objects.requireNonNull(playerId, "playerId");
        seeds = List.copyOf(seeds);
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_COUNT + ", was " + count);
        }
    }

    /** Tracks to follow the current queue. */
    public MixRequest(String playerId, List<TrackId> seeds, int count) {
        this(playerId, seeds, count, false);
    }
}
