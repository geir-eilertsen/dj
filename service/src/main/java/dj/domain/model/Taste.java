package dj.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The artists a listener likes, as far as DJ can tell from what they play.
 *
 * @param artists display names, favourite first, each weighted 0..1 (the favourite is 1)
 */
public record Taste(Map<String, Double> artists) {

    public static final Taste NONE = new Taste(Map.of());

    public Taste {
        artists = new LinkedHashMap<>(Objects.requireNonNull(artists, "artists"));
    }

    public boolean isEmpty() {
        return artists.isEmpty();
    }
}
