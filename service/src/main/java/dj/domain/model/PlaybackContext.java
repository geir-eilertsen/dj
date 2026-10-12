package dj.domain.model;

import java.time.LocalTime;
import java.util.List;

/**
 * What is happening on the player right now; input for musical judgment.
 *
 * @param queue the player's queue in play order: what has played and what is still to come
 */
public record PlaybackContext(String playerId, String playerName, List<TrackId> queue, LocalTime timeOfDay) {

    public PlaybackContext {
        queue = List.copyOf(queue);
    }
}
