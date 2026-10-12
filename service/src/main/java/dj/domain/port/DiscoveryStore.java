package dj.domain.port;

import dj.domain.model.Track;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Local store of discovered music: playable tracks that aren't in the library, and the artists
 * the user plays straight from a streaming service. Reads must be fast: they happen on every mix.
 */
public interface DiscoveryStore {

    /** Every discovered track. */
    List<Track> allTracks();

    /** When this artist's tracks were last discovered, if ever. */
    Optional<Instant> discoveredAt(String artist);

    /** Replaces the discovered tracks for this artist. */
    void save(String artist, List<Track> tracks, Instant discoveredAt);

    /** Records that the user played this artist from a streaming service. */
    void rememberListened(String artist, Instant heardAt);

    List<String> listenedArtists();
}
