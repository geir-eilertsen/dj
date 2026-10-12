package dj.domain.model;

import java.util.Objects;

/** A track the LLM chose, and its one-line reason. */
public record Pick(TrackId id, String reason) {

    public Pick {
        Objects.requireNonNull(id, "id");
        reason = Objects.requireNonNullElse(reason, "").strip();
    }
}
