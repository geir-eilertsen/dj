package dj.domain.port;

import dj.domain.model.Track;

import java.util.Optional;

/** A streaming catalogue (TIDAL, Spotify) that can turn a song into a playable track. */
public interface CatalogPort {

    /**
     * Finds this song by this artist, rejecting covers and karaoke versions.
     *
     * @return empty if the catalogue doesn't have it
     * @throws RuntimeException if the catalogue can't be searched
     */
    Optional<Track> find(String artist, String title);
}
