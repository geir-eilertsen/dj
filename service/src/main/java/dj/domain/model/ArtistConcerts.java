package dj.domain.model;

import java.time.Instant;
import java.util.List;

/** What a concert lookup for an artist found, and when. An empty list is a valid answer. */
public record ArtistConcerts(String artist, List<Concert> concerts, Instant checkedAt) {

    public ArtistConcerts {
        concerts = List.copyOf(concerts);
    }
}
