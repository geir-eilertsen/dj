package dj.domain.port;

import dj.domain.model.NowPlaying;

import java.util.List;

/** What every connected player is playing right now. */
public interface PlayerActivityPort {

    /** @throws RuntimeException if LMS can't be asked */
    List<NowPlaying> nowPlaying();
}
