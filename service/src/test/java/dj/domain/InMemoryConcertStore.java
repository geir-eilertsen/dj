package dj.domain;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.ArtistNames;
import dj.domain.port.ConcertStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake for {@link ConcertStore}. */
final class InMemoryConcertStore implements ConcertStore {

    private final Map<String, ArtistConcerts> byArtist = new LinkedHashMap<>();

    @Override
    public Optional<ArtistConcerts> get(String artist) {
        return Optional.ofNullable(byArtist.get(ArtistNames.key(artist)));
    }

    @Override
    public void save(ArtistConcerts concerts) {
        byArtist.put(ArtistNames.key(concerts.artist()), concerts);
    }

    @Override
    public List<ArtistConcerts> all() {
        return List.copyOf(byArtist.values());
    }
}
