package dj.domain;

import dj.domain.model.ArtistNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ArtistNamesTest {

    @Test
    void ignoresCaseAccentsPunctuationAndLeadingThe() {
        assertEquals(ArtistNames.key("TOOL"), ArtistNames.key("Tool"));
        assertEquals(ArtistNames.key("The Cure"), ArtistNames.key("Cure"));
        assertEquals(ArtistNames.key("Simon & Garfunkel"), ArtistNames.key("Simon and Garfunkel"));
        assertEquals(ArtistNames.key("Motörhead"), ArtistNames.key("Motorhead"));
        assertEquals(ArtistNames.key("Gasolin'"), ArtistNames.key("Gasolin"));
    }

    @Test
    void keepsDistinctArtistsDistinct() {
        assertNotEquals(ArtistNames.key("Tønes"), ArtistNames.key("Tones"));
        assertEquals("theatre of tragedy", ArtistNames.key("Theatre of Tragedy"));
    }

    @Test
    void handlesNull() {
        assertEquals("", ArtistNames.key(null));
    }
}
