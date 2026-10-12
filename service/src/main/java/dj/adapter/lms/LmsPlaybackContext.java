package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
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
 * <p>Queue entries keep LMS's own ids: library tracks have positive ids, tracks played straight
 * from a streaming service have negative ones. Either way their artist and title are known.
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
    public PlaybackContext contextFor(String playerId) {
        LocalTime now = LocalTime.now(clock);
        try {
            JsonNode status = lms.request(playerId, List.of("status", 0, MAX_QUEUE, "tags:aluy"));
            return new PlaybackContext(playerId, status.path("player_name").asText(playerId), queue(status), now);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read status for " + playerId + "; mixing without the queue", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new PlaybackContext(playerId, playerId, List.of(), now);
    }

    static List<Track> queue(JsonNode status) {
        List<Track> queue = new ArrayList<>();
        for (JsonNode item : status.path("playlist_loop")) {
            String id = item.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            queue.add(new Track(TrackId.of(id),
                    item.path("url").asText(""),
                    item.path("title").asText(""),
                    item.path("artist").asText(""),
                    item.path("album").asText(""),
                    item.path("year").asInt(0),
                    0));
        }
        return queue;
    }
}
