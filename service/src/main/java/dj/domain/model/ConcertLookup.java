package dj.domain.model;

import java.time.Instant;
import java.util.List;

/** What one provider knew of an artist's concerts, and when. An empty list is a valid answer. */
public record ConcertLookup(String artist, String provider, List<Concert> concerts, Instant checkedAt) {

    public ConcertLookup {
        concerts = List.copyOf(concerts);
    }
}
