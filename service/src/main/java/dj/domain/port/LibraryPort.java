package dj.domain.port;

import dj.domain.model.Track;
import dj.domain.model.TrackId;

import java.util.List;
import java.util.Set;

/** Read access to the music library. */
public interface LibraryPort {

    /**
     * Returns up to {@code count} random tracks, none of which are in {@code exclude}.
     *
     * @throws LibraryUnavailableException if the library can't be read
     */
    List<Track> randomTracks(int count, Set<TrackId> exclude);
}
