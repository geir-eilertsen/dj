package dj.domain.model;

import java.time.LocalTime;
import java.util.List;

/**
 * What is happening on the player right now; input for musical judgment.
 *
 * @param playing whether the player is playing right now (false when stopped or paused)
 * @param queue   the player's queue in play order, as LMS reports it. Tracks played from a
 *                streaming service rather than the library are included, with ids that don't
 *                match any library track.
 */
public record PlaybackContext(String playerId, String playerName, boolean playing, List<Track> queue,
                              LocalTime timeOfDay) {

    public PlaybackContext {
        queue = List.copyOf(queue);
    }
}
