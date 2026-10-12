package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import dj.domain.model.PlaybackContext;
import dj.domain.model.TrackId;
import dj.domain.port.PlaybackContextPort;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the player's name and queue from LMS ({@code status}).
 *
 * <p>If LMS can't be asked, the request's seeds stand in for the queue so a mix still happens.
 */
public class LmsPlaybackContext implements PlaybackContextPort {

    private static final Logger LOG = System.getLogger(LmsPlaybackContext.class.getName());
    private static final int MAX_QUEUE = 500;

    private final LmsJsonRpcClient lms;
    private final Clock clock;

    public LmsPlaybackContext(LmsJsonRpcClient lms, Clock clock) {
        this.lms = lms;
        this.clock = clock;
    }

    @Override
    public PlaybackContext contextFor(String playerId, List<TrackId> seeds) {
        LocalTime now = LocalTime.now(clock);
        try {
            JsonNode status = lms.request(playerId, List.of("status", 0, MAX_QUEUE, "tags:"));
            List<TrackId> queue = new ArrayList<>();
            for (JsonNode item : status.path("playlist_loop")) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    queue.add(TrackId.of(id));
                }
            }
            String name = status.path("player_name").asText(playerId);
            return new PlaybackContext(playerId, name, queue.isEmpty() ? seeds : queue, now);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read status for " + playerId + "; using the request seeds", e);
            return new PlaybackContext(playerId, playerId, seeds, now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PlaybackContext(playerId, playerId, seeds, now);
        }
    }
}
