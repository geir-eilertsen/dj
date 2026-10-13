package dj.domain.port;

import dj.domain.model.ArtistProfile;

import java.util.Collection;
import java.util.Optional;

/** Local, offline store of artist profiles. Lookups must be fast: they happen on every mix. */
public interface ArtistProfileStore {

    Optional<ArtistProfile> find(String artistName);

    void save(ArtistProfile profile);

    int size();

    /** Every profile; for occasional scans, not for the mix path. */
    Collection<ArtistProfile> all();
}
