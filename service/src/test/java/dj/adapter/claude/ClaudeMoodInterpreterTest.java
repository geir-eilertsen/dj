package dj.adapter.claude;

import dj.domain.model.Mood;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The prompt and response handling; no network. */
class ClaudeMoodInterpreterTest {

    @Test
    void givesTheKnownTagsAndTheRequest() {
        assertEquals("Known tags: rock, danish\n\nRequest: dansk rock",
                ClaudeMoodInterpreter.userMessage("dansk rock", List.of("rock", "danish")));
    }

    @Test
    void readsTheStyle() {
        Mood.Style style = ClaudeMoodInterpreter.parse("""
                {"tags":["Progressive Rock","ambient",""],"artists":["Pink Floyd"],"fromYear":1970,"toYear":1979}""");

        assertEquals(new Mood.Style(List.of("progressive rock", "ambient"), List.of("Pink Floyd"), 1970, 1979), style);
    }

    @Test
    void capsTagsAndArtists() {
        Mood.Style style = ClaudeMoodInterpreter.parse("""
                {"tags":["a","b","c","d","e","f","g","h","i"],"artists":["1","2","3","4","5","6"],"fromYear":0,"toYear":0}""");

        assertEquals(ClaudeMoodInterpreter.MAX_TAGS, style.tags().size());
        assertEquals(ClaudeMoodInterpreter.MAX_ARTISTS, style.artists().size());
    }

    @Test
    void rejectsAnswersThatAreNotJson() {
        assertThrows(IllegalStateException.class, () -> ClaudeMoodInterpreter.parse("Sounds lovely!"));
    }
}
