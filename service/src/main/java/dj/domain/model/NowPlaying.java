package dj.domain.model;

/**
 * What a player is doing right now.
 *
 * @param queueIndex     position of the current track in the player's queue
 * @param elapsedSeconds how far into the current track playback is
 * @param durationSeconds length of the current track, or 0 if unknown (e.g. radio)
 */
public record NowPlaying(String playerId, boolean playing, Track track, int queueIndex, double elapsedSeconds,
                         double durationSeconds) {
}
