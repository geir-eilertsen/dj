package dj.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tracks chosen for a player, how they were chosen, and why (when the LLM said).
 *
 * @param reasons one-line reasons by track id; tracks the LLM didn't pick have none
 */
public record Mix(List<Track> tracks, Source source, Map<TrackId, String> reasons) {

    public enum Source {
        /** The LLM picked and ordered (at least some of) the tracks. */
        LLM,
        /** Candidate order was used because the LLM was unavailable, slow or gave nothing usable. */
        FALLBACK
    }

    public Mix {
        tracks = List.copyOf(tracks);
        reasons = Map.copyOf(reasons);
    }

    public Mix(List<Track> tracks, Source source) {
        this(tracks, source, Map.of());
    }

    public Optional<String> reasonFor(TrackId id) {
        return Optional.ofNullable(reasons.get(id));
    }
}
