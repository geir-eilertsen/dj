package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.ConcertLookup;
import dj.domain.port.ConcertProviderChoices;
import dj.domain.port.ConcertStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake for {@link ConcertStore} and {@link ConcertProviderChoices}. */
final class InMemoryConcertStore implements ConcertStore, ConcertProviderChoices {

    private final Map<String, ConcertLookup> lookups = new LinkedHashMap<>();
    private Map<String, Boolean> choices = Map.of();

    @Override
    public Optional<ConcertLookup> get(String artist, String provider) {
        return Optional.ofNullable(lookups.get(ArtistNames.key(artist) + "|" + provider));
    }

    @Override
    public void save(ConcertLookup lookup) {
        lookups.put(ArtistNames.key(lookup.artist()) + "|" + lookup.provider(), lookup);
    }

    @Override
    public List<ConcertLookup> all() {
        return List.copyOf(lookups.values());
    }

    @Override
    public Map<String, Boolean> choices() {
        return choices;
    }

    @Override
    public void save(Map<String, Boolean> choices) {
        this.choices = Map.copyOf(choices);
    }
}
