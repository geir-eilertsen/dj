package dj.domain.port;

import dj.domain.model.Skip;
import dj.domain.model.Track;

import java.time.Instant;
import java.util.List;

/** What DJ suggested to each player, and which suggestions the listener skipped. */
public interface FeedbackStore {

    void recordSuggested(String playerId, List<Track> tracks, Instant at);

    /** Whether this URL was suggested to this player since {@code since}. */
    boolean wasSuggested(String playerId, String url, Instant since);

    void recordSkip(Skip skip);

    /** All skips since {@code since}, on any player. */
    List<Skip> skipsSince(Instant since);
}
