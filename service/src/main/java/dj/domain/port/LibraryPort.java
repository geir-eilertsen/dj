package dj.domain.port;

import dj.domain.model.Track;

import java.util.List;

/** Read access to the music library. */
public interface LibraryPort {

    /**
     * Returns every playable track in the library.
     *
     * @throws LibraryUnavailableException if the library can't be read
     */
    List<Track> allTracks();
}
