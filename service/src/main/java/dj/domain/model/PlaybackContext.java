package dj.domain.model;

import java.time.LocalTime;
import java.util.List;

/**
 * What is happening on the player right now; input for musical judgment.
 *
 * @param recentTracks recently played or queued tracks, oldest first
 */
public record PlaybackContext(String playerId, String playerName, List<TrackId> recentTracks, LocalTime timeOfDay) {

    public PlaybackContext {
        recentTracks = List.copyOf(recentTracks);
    }
}
