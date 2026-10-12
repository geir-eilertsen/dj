package dj.adapter.stub;

import dj.domain.model.PlaybackContext;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.LlmPort;

import java.util.List;

/** Placeholder until the Ollama and Claude adapters exist: never has an opinion. */
public class NoLlm implements LlmPort {

    @Override
    public List<TrackId> choose(PlaybackContext context, List<Track> candidates, int count) {
        return List.of();
    }
}
