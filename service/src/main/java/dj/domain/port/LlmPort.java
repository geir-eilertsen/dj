package dj.domain.port;

import dj.domain.model.Pick;
import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;

import java.util.List;

/**
 * Musical judgment: picks and orders tracks from a candidate list, with a reason for each.
 *
 * <p>Implementations answer with IDs only. The caller never trusts the answer: IDs that are not
 * in {@code candidates} are discarded.
 */
public interface LlmPort {

    /** Returns up to {@code count} picks in play order. May be empty. */
    List<Pick> choose(PlaybackContext context, List<Track> candidates, int count);
}
