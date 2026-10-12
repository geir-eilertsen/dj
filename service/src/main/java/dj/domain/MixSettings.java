package dj.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning for {@link MixService}.
 *
 * @param candidatePoolSize how many candidates to offer the LLM
 * @param llmTimeout        how long to wait for the LLM
 * @param seedLookupTimeout how long to wait for looking up unknown seed artists
 * @param discoveryShare    fraction of each mix (0..1) to fill with discovered music when
 *                          relevant discovered tracks exist
 */
public record MixSettings(int candidatePoolSize, Duration llmTimeout, Duration seedLookupTimeout, double discoveryShare) {

    /** Discovered tracks must relate at least this much to the seeds (see {@link CandidateScorer}). */
    static final double DISCOVERY_MIN_RELEVANCE = 0.25;

    public MixSettings {
        if (candidatePoolSize < 1) {
            throw new IllegalArgumentException("candidatePoolSize must be positive");
        }
        Objects.requireNonNull(llmTimeout);
        Objects.requireNonNull(seedLookupTimeout);
        if (discoveryShare < 0 || discoveryShare > 1) {
            throw new IllegalArgumentException("discoveryShare must be between 0 and 1");
        }
    }
}
