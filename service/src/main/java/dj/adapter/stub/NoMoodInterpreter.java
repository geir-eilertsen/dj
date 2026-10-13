package dj.adapter.stub;

import dj.domain.model.Mood;
import dj.domain.port.MoodInterpreter;

import java.util.List;

/** Used when no LLM is configured: moods are understood by keywords only. */
public class NoMoodInterpreter implements MoodInterpreter {

    @Override
    public Mood.Style interpret(String request, List<String> knownTags) {
        return Mood.Style.NONE;
    }
}
