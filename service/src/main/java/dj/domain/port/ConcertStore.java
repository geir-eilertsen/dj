package dj.domain.port;

import dj.domain.model.ArtistConcerts;

import java.util.List;
import java.util.Optional;

/** The latest concert lookup per artist (matched with {@code ArtistNames.key}). */
public interface ConcertStore {

    Optional<ArtistConcerts> get(String artist);

    /** Replaces what is known about this artist's concerts. */
    void save(ArtistConcerts concerts);

    List<ArtistConcerts> all();
}
