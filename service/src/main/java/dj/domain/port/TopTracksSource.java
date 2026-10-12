package dj.domain.port;

import java.util.List;

/** An online source of an artist's most popular songs (e.g. Last.fm). Background use only. */
public interface TopTracksSource {

    /**
     * @return up to {@code limit} song titles, most popular first; empty if the artist is unknown
     * @throws RuntimeException if the source can't be reached
     */
    List<String> topTracks(String artistName, int limit);
}
