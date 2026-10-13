package dj.domain;

import dj.domain.model.Mood;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChristmasSongsTest {

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 13);

    @ParameterizedTest
    @ValueSource(strings = {"Gnags' Julesang", "Last Christmas", "Happy Xmas (War Is Over)", "Jul i Svingen",
            "Det er jul igjen", "O Helga Natt", "Stille Natt", "Feliz Navidad", "Driving Home for Christmas - Remastered",
            "Jingle Bell Rock", "Sleigh Ride", "Julemusikk", "Leise rieselt der Schnee (Weihnachtslied)", "Juletrefest", "Have Yourself a Merry Little X-Mas"})
    void recognisesChristmasTitles(String title) {
        assertTrue(ChristmasSongs.isChristmasSong(track(title, "")), title);
    }

    @ParameterizedTest
    @ValueSource(strings = {"July Morning", "Julie", "Julia", "Juliet", "Santa Monica", "Adventures", "Carol",
            "Jules", "Joy to the World", "Noel's Song", "Ju-Ju"})
    void leavesOtherTitlesAlone(String title) {
        assertFalse(ChristmasSongs.isChristmasSong(track(title, "")), title);
    }

    @Test
    void aSongOnAChristmasAlbumIsAChristmasSong() {
        assertTrue(ChristmasSongs.isChristmasSong(track("Hallelujah", "A Very Special Christmas")));
    }

    @Test
    void theSeasonRunsFromAdventToTheThirteenthDay() {
        // First Sunday of Advent: 29 November 2026, 28 November 2027.
        assertFalse(ChristmasSongs.inSeason(LocalDate.of(2026, 11, 28)));
        assertTrue(ChristmasSongs.inSeason(LocalDate.of(2026, 11, 29)));
        assertTrue(ChristmasSongs.inSeason(LocalDate.of(2026, 12, 31)));
        assertTrue(ChristmasSongs.inSeason(LocalDate.of(2027, 1, 6)));
        assertFalse(ChristmasSongs.inSeason(LocalDate.of(2027, 1, 7)));
        assertFalse(ChristmasSongs.inSeason(LocalDate.of(2027, 11, 27)));
        assertTrue(ChristmasSongs.inSeason(LocalDate.of(2027, 11, 28)));
        assertFalse(ChristmasSongs.inSeason(OCTOBER));
    }

    @Test
    void outOfSeasonOnlyWhenAskedFor() {
        assertFalse(ChristmasSongs.wanted(OCTOBER, Mood.Style.NONE, "mellow prog", List.of(track("Sommer", ""))));
        assertTrue(ChristmasSongs.wanted(OCTOBER, Mood.Style.NONE, "julemusikk", List.of()));
        assertTrue(ChristmasSongs.wanted(OCTOBER, new Mood.Style(List.of("christmas"), List.of(), 0, 0), "cosy", List.of()));
        assertTrue(ChristmasSongs.wanted(OCTOBER, Mood.Style.NONE, "", List.of(track("Last Christmas", ""))));
    }

    private static Track track(String title, String album) {
        return new Track(TrackId.of("1"), "tidal://1.flc", title, "Someone", album, 0, 0);
    }
}
