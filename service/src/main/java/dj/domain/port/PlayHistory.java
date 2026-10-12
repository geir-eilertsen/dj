package dj.domain.port;

import dj.domain.model.Play;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** DJ's own listening log. LMS only counts plays; it doesn't record when they happened. */
public interface PlayHistory {

    void record(Play play);

    /** Plays on this player that started at or after {@code since}, oldest first. */
    List<Play> playsOn(String playerId, Instant since);

    /** The most recent play on this player, if any. */
    Optional<Play> lastPlayOn(String playerId);
}
