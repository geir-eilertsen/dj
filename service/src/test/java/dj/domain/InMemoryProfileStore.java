package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.port.ArtistProfileStore;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake for {@link ArtistProfileStore}. */
final class InMemoryProfileStore implements ArtistProfileStore {

    static final Instant FETCHED = Instant.parse("2026-10-01T00:00:00Z");

    private final Map<String, ArtistProfile> profiles = new HashMap<>();

    @Override
    public Optional<ArtistProfile> find(String artistName) {
        return Optional.ofNullable(profiles.get(ArtistNames.key(artistName)));
    }

    @Override
    public void save(ArtistProfile profile) {
        profiles.put(ArtistNames.key(profile.name()), profile);
    }

    @Override
    public int size() {
        return profiles.size();
    }

    /** Adds a profile with the given tags (weight 1) and similar artists ("name:match"). */
    InMemoryProfileStore with(String artist, List<String> tags, String... similar) {
        save(new ArtistProfile(artist,
                tags.stream().map(t -> new ArtistProfile.Tag(t, 1.0)).toList(),
                Arrays.stream(similar).map(s -> {
                    String[] parts = s.split(":");
                    return new ArtistProfile.SimilarArtist(parts[0], Double.parseDouble(parts[1]));
                }).toList(),
                FETCHED));
        return this;
    }
}
