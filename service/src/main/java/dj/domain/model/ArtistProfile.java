package dj.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What is known about an artist from outside the library: style tags and similar artists.
 *
 * <p>A profile with no tags and no similar artists records that the artist was looked up but
 * not found, so the lookup isn't repeated until the profile is stale.
 *
 * @param fetchedAt when the information was fetched
 */
public record ArtistProfile(String name, List<Tag> tags, List<SimilarArtist> similar, Instant fetchedAt) {

    /** A style tag such as "shoegaze", weighted 0..1 by how strongly it applies. */
    public record Tag(String name, double weight) {
    }

    /** Another artist, with a 0..1 similarity score. */
    public record SimilarArtist(String name, double match) {
    }

    public ArtistProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        tags = List.copyOf(tags);
        similar = List.copyOf(similar);
    }

    public static ArtistProfile unknown(String name, Instant fetchedAt) {
        return new ArtistProfile(name, List.of(), List.of(), fetchedAt);
    }

    public boolean isUnknown() {
        return tags.isEmpty() && similar.isEmpty();
    }
}
