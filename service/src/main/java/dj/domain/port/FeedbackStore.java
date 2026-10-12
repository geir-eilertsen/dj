package dj.domain.port;

import dj.domain.model.Skip;
import dj.domain.model.Track;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What DJ suggested to each player, and which suggestions the listener skipped. */
public interface FeedbackStore {

    /** @param reasons why each track was suggested, by URL; tracks without a reason are absent */
    void recordSuggested(String playerId, List<Track> tracks, Map<String, String> reasons, Instant at);

    /** The reason the most recent suggestion of this URL to this player was made, if any. */
    Optional<String> reasonFor(String playerId, String url);

    /** Whether this URL was suggested to this player since {@code since}. */
    boolean wasSuggested(String playerId, String url, Instant since);

    void recordSkip(Skip skip);

    /** All skips since {@code since}, on any player. */
    List<Skip> skipsSince(Instant since);
}
