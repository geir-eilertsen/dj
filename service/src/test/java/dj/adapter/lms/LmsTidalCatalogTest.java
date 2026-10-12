package dj.adapter.lms;

import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmsTidalCatalogTest {

    // Shaped like the real answer for "Shu-bi-dua Den Røde Tråd" (Songs, menu:1).
    private static final String RESULTS = """
            {"item_loop":[
              {"text":"Den Røde Tråd (Originally Performed By Shu-bi-dua)\\nMIDIFine Systems",
               "presetParams":{"favorites_url":"tidal://103367123.flc"}},
              {"text":"Den Røde Tråd\\nShu-Bi-Dua 2.0","presetParams":{"favorites_url":"tidal://471802515.flc"}},
              {"text":"Den Røde Tråd - 2011 Remaster\\nShu-bi-dua","presetParams":{"favorites_url":"tidal://23069777.flc"}},
              {"text":"Den Røde Tråd\\nShu-bi-dua","presetParams":{"favorites_url":"tidal://13708687.flc"}}]}""";

    @Test
    void picksTheFirstResultByTheRightArtistAndSong() throws Exception {
        Optional<Track> track = LmsTidalCatalog.bestMatch(new ObjectMapper().readTree(RESULTS), "Shu-bi-dua", "Den røde tråd");

        assertEquals(Optional.of(new Track(TrackId.of("tidal://23069777.flc"), "tidal://23069777.flc",
                "Den Røde Tråd - 2011 Remaster", "Shu-bi-dua", "", 0, 0)), track);
    }

    @Test
    void rejectsCoversAndOtherSongs() throws Exception {
        var results = new ObjectMapper().readTree(RESULTS);

        assertTrue(LmsTidalCatalog.bestMatch(results, "Shu-bi-dua", "Rocking Magic").isEmpty());
        assertTrue(LmsTidalCatalog.bestMatch(results, "MIDIFine Systems", "Something Else").isEmpty());
    }

    @Test
    void matchesStylisedArtistNames() throws Exception {
        var results = new ObjectMapper().readTree("""
                {"item_loop":[{"text":"Bag Duggede Ruder\\ntv·2","presetParams":{"favorites_url":"tidal://5893422.flc"}}]}""");

        assertTrue(LmsTidalCatalog.bestMatch(results, "TV-2", "Bag Duggede Ruder").isPresent());
    }
}
