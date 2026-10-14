package dj.domain.port;

import dj.domain.model.ConcertLookup;

import java.util.List;
import java.util.Optional;

/** The latest concert lookup per artist (matched with {@code ArtistNames.key}) and provider. */
public interface ConcertStore {

    Optional<ConcertLookup> get(String artist, String provider);

    /** Replaces what this provider knew about this artist's concerts. */
    void save(ConcertLookup lookup);

    List<ConcertLookup> all();
}
