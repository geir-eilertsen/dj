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
    void groupsSideProjectsAndLineUps() {
        assertEquals("devin townsend", ArtistNames.groupKey("Devin Townsend Project"));
        assertEquals("devin townsend", ArtistNames.groupKey("Devin Townsend Band"));
        assertEquals("devin townsend", ArtistNames.groupKey("Devin Townsend"));
        assertEquals("nick cave", ArtistNames.groupKey("Nick Cave & The Bad Seeds"));
        assertEquals("kygo", ArtistNames.groupKey("Kygo feat. James Vincent McMorrow"));
        assertEquals("jimi hendrix", ArtistNames.groupKey("The Jimi Hendrix Experience"));
    }

    @Test
    void leavesOrdinaryNamesAlone() {
        assertEquals("band", ArtistNames.groupKey("The Band"));
        assertEquals("simon and garfunkel", ArtistNames.groupKey("Simon & Garfunkel"));
        assertEquals("opeth", ArtistNames.groupKey("Opeth"));
        assertNotEquals(ArtistNames.groupKey("Steven Wilson"), ArtistNames.groupKey("Porcupine Tree"));
    }

    @Test
    void handlesNull() {
        assertEquals("", ArtistNames.key(null));
    }
}
