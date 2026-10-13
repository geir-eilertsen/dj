package dj.domain.port;

import dj.domain.model.Mood;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The moods set on each player. */
public interface MoodStore {

    void save(String playerId, Mood mood);

    /** The mood set most recently on the player, unless it has been cleared since. */
    Optional<Mood> latest(String playerId);

    /** Clears the player's mood; it stays among the recent ones. */
    void clear(String playerId, Instant at);

    /** Moods set on the player, newest first, one per text (ignoring case). */
    List<Mood> recent(String playerId, int limit);
}
