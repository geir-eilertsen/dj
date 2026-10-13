package dj.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * What the LLM is told about the situation it picks music for.
 *
 * @param fresh the picks start a new queue: they needn't follow what was just played
 * @param mood  what the listener asked for, if they did
 */
public record MixBrief(PlaybackContext context, boolean fresh, Optional<Mood> mood) {

    public MixBrief {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mood, "mood");
    }
}
