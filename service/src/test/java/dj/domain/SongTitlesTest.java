package dj.domain;

import dj.domain.model.SongTitles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SongTitlesTest {

    @Test
    void ignoresVersionTags() {
        String forest = SongTitles.key("Beyond the Great Vast Forest");
        assertEquals(forest, SongTitles.key("Beyond the Great Vast Forest (Alternative Mix 1993) - Previously Unreleased"));
        assertEquals(forest, SongTitles.key("Beyond the Great Vast Forest - Re-Mastered"));
        assertEquals(SongTitles.key("The Burning Shadows of Silence"),
                SongTitles.key("The Burning Shadows of Silence - The Akkerhaugen Tapes 1993"));
        assertEquals("dreams", SongTitles.key("Dreams (2001 Remaster)"));
        assertEquals("adagio", SongTitles.key("Adagio - 2012 Version"));
        assertEquals("burning down the house", SongTitles.key("Burning Down the House (Live)"));
        assertEquals(SongTitles.key("The Company"), SongTitles.key("The Company - Demo Version"));
    }

    @Test
    void ignoresFeaturedArtists() {
        assertEquals("carry me", SongTitles.key("Carry Me (feat. Julia Michaels)"));
        assertEquals(SongTitles.key("Anabasis (Pt.2)"),
                SongTitles.key("Anabasis (Pt.2) (feat. Angel Vivaldi, Steffen Kummerer, Merethe Soltvedt)"));
    }

    @Test
    void stripsReleaseNamesTaggedAsAnEp() {
        assertEquals(SongTitles.key("The Ancient Queen"),
                SongTitles.key("The Ancient Queen - As the Shadows Rise 7\" EP 1994 (Re-Mastered)"));
    }

    @Test
    void ignoresPunctuation() {
        assertEquals(SongTitles.key("10,000 Lovers (In One)"), SongTitles.key("10.000 Lovers (In One)"));
    }

    @Test
    void keepsPartsAndSubtitles() {
        assertNotEquals(SongTitles.key("Anabasis (Pt.1)"), SongTitles.key("Anabasis (Pt.2)"));
        assertEquals("10 000 days wings pt 2", SongTitles.key("10,000 Days (Wings Pt 2)"));
        assertNotEquals(SongTitles.key("Alone"), SongTitles.key("Alone (Solo)"));
        // Kept brackets containing regex replacement characters must survive intact.
        assertEquals("money 100 bill a b", SongTitles.key("Money ($100 Bill \\a/b)"));
    }
}
