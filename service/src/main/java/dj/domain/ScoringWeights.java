package dj.domain;

/**
 * Tuning knobs for {@link CandidateScorer}.
 *
 * @param artist             weight of the similar-artist signal
 * @param tags               weight of style-tag overlap with the seeds
 * @param plays              weight of how much the track has been played
 * @param year               weight of release-year closeness to the seeds
 * @param jitter             weight of random noise, so repeated mixes differ
 * @param sameArtist         artist similarity of a seed artist to itself (below 1 so the mix moves on)
 * @param recentArtistFactor score multiplier for artists in the last few queued tracks
 * @param repeatArtistFactor score multiplier per earlier pick of the same artist within one mix
 * @param recentArtistWindow how many tracks at the end of the queue count as "recent"
 * @param seedDecay          weight multiplier per step back in the seed list (latest seed counts most)
 * @param skippedSong        score multiplier per (fresh) skip of the song
 * @param skippedArtist      score multiplier per (fresh) skip of any song by the artist
 */
public record ScoringWeights(double artist, double tags, double plays, double year, double jitter,
                             double sameArtist, double recentArtistFactor, double repeatArtistFactor,
                             int recentArtistWindow, double seedDecay, double skippedSong, double skippedArtist) {

    public static final ScoringWeights DEFAULTS =
            new ScoringWeights(1.0, 0.8, 0.2, 0.15, 0.25, 0.6, 0.3, 0.5, 4, 0.7, 0.4, 0.85);

    public ScoringWeights withJitter(double jitter) {
        return new ScoringWeights(artist, tags, plays, year, jitter, sameArtist, recentArtistFactor,
                repeatArtistFactor, recentArtistWindow, seedDecay, skippedSong, skippedArtist);
    }
}
