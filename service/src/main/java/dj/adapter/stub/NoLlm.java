package dj.adapter.stub;

import dj.domain.model.MixBrief;
import dj.domain.model.Pick;
import dj.domain.model.Track;
import dj.domain.port.LlmPort;

import java.util.List;

/** Used when no LLM is configured: never has an opinion, so candidate order is used. */
public class NoLlm implements LlmPort {

    @Override
    public List<Pick> choose(MixBrief brief, List<Track> candidates, int count) {
        return List.of();
    }
}
