package dj.adapter.stub;

import dj.domain.model.PlaybackContext;
import dj.domain.model.TrackId;
import dj.domain.port.PlaybackContextPort;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;

/**
 * Context from the request alone: seeds as recent history, local time of day.
 * A later adapter will ask LMS for the player name and real history.
 */
public class BasicPlaybackContext implements PlaybackContextPort {

    private final Clock clock;

    public BasicPlaybackContext(Clock clock) {
        this.clock = clock;
    }

    @Override
    public PlaybackContext contextFor(String playerId, List<TrackId> seeds) {
        return new PlaybackContext(playerId, playerId, seeds, LocalTime.now(clock));
    }
}
