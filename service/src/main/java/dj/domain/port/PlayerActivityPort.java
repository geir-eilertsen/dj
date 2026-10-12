package dj.domain.port;

import dj.domain.model.NowPlaying;
import dj.domain.model.Track;

import java.util.List;

/** What every connected player is playing right now. */
public interface PlayerActivityPort {

    /** @throws RuntimeException if LMS can't be asked */
    List<NowPlaying> nowPlaying();

    /** Tracks at positions {@code from} (inclusive) to {@code to} (exclusive) of the player's queue. */
    List<Track> queueSlice(String playerId, int from, int to);
}
