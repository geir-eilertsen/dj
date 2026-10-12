package dj.domain.port;

import dj.domain.model.ArtistProfile;

import java.util.Optional;

/** An online source of artist information (e.g. Last.fm). Only used by background enrichment. */
public interface ArtistInfoSource {

    /**
     * Fetches the artist's profile.
     *
     * @return empty if the source doesn't know the artist
     * @throws RuntimeException if the source can't be reached or refuses the request
     */
    Optional<ArtistProfile> fetch(String artistName);
}
