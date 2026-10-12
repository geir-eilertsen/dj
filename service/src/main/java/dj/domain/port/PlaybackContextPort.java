package dj.domain.port;

import dj.domain.model.PlaybackContext;

/** Describes what is currently happening on a player. */
public interface PlaybackContextPort {

    /** Never fails: if the player can't be asked, the context has an empty queue. */
    PlaybackContext contextFor(String playerId);
}
