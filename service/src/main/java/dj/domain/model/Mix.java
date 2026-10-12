package dj.domain.model;

import java.util.List;

/** The tracks chosen for a player, and how they were chosen. */
public record Mix(List<Track> tracks, Source source) {

    public enum Source {
        /** The LLM picked and ordered (at least some of) the tracks. */
        LLM,
        /** Candidate order was used because the LLM was unavailable, slow or gave nothing usable. */
        FALLBACK
    }

    public Mix {
        tracks = List.copyOf(tracks);
    }
}
