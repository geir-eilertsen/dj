package dj.domain.port;

import dj.domain.model.Mood;

import java.util.List;

/**
 * Understands a listener's request ("mellow prog for Sunday morning") as a musical style.
 * Called when a mood is set, never while mixing; may be slow and need the internet.
 */
public interface MoodInterpreter {

    /**
     * @param knownTags the style tags the listener's artists are tagged with, most common first;
     *                  matching only works with these
     * @return the style; {@link Mood.Style#NONE} if the request can't be understood
     */
    Mood.Style interpret(String request, List<String> knownTags);
}
