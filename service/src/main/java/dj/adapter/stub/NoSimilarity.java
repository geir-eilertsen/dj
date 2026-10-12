package dj.adapter.stub;

import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.SimilarityPort;

import java.util.List;

/** Placeholder until a real similarity source exists (see the roadmap in CLAUDE.md). */
public class NoSimilarity implements SimilarityPort {

    @Override
    public List<Track> neighbours(List<TrackId> seeds, int limit) {
        return List.of();
    }
}
