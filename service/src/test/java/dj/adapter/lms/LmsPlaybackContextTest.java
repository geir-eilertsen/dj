package dj.adapter.lms;

import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LmsPlaybackContextTest {

    @Test
    void readsLibraryAndStreamedTracksFromTheQueue() throws Exception {
        var status = new ObjectMapper().readTree("""
                {"player_name":"Geir PC","playlist_loop":[
                  {"playlist index":0,"id":-104704494700608,"title":"Den Store Dag","artist":"Kim Larsen",
                   "album":"Midt Om Natten","url":"tidal://105920856.flc"},
                  {"playlist index":1,"id":119,"title":"§1","artist":"Opeth","album":"The Last Will And Testament",
                   "year":"2024","url":"tidal://389622359.flc"},
                  {"playlist index":2,"title":"No id"}]}""");

        List<Track> queue = LmsPlaybackContext.queue(status);

        assertEquals(List.of(
                new Track(TrackId.of("-104704494700608"), "tidal://105920856.flc", "Den Store Dag", "Kim Larsen",
                        "Midt Om Natten", 0, 0),
                new Track(TrackId.of("119"), "tidal://389622359.flc", "§1", "Opeth", "The Last Will And Testament",
                        2024, 0)), queue);
    }
}
