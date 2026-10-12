package dj.domain.port;

import dj.domain.model.PlaybackContext;
import dj.domain.model.TrackId;

import java.util.List;

/** Describes what is currently happening on a player. */
public interface PlaybackContextPort {

    PlaybackContext contextFor(String playerId, List<TrackId> seeds);
}
