package dj.domain.port;

import dj.domain.model.Track;
import dj.domain.model.TrackId;

import java.util.List;

/** Finds library tracks that sound like the seeds. */
public interface SimilarityPort {

    /** Returns up to {@code limit} library tracks, most similar first. May be empty. */
    List<Track> neighbours(List<TrackId> seeds, int limit);
}
