package dj.adapter.lms;

import com.fasterxml.jackson.databind.ObjectMapper;
import dj.domain.model.NowPlaying;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmsPlayerActivityTest {

    @Test
    void readsTheCurrentTrackAndPosition() throws Exception {
        var status = new ObjectMapper().readTree("""
                {"mode":"play","time":81.72,"duration":228.33,"playlist_loop":[
                  {"playlist index":9,"id":-104704481239800,"title":"Sommer","artist":"Kim Larsen","url":"tidal://105920863.flc"}]}""");

        NowPlaying np = LmsPlayerActivity.parse("b0:3c:dc:72:f2:72", status).orElseThrow();

        assertTrue(np.playing());
        assertEquals("Kim Larsen", np.track().artist());
        assertEquals(81.72, np.elapsedSeconds());
        assertEquals(228.33, np.durationSeconds());
    }

    @Test
    void hasNothingForAnEmptyQueue() throws Exception {
        var status = new ObjectMapper().readTree("""
                {"mode":"stop","playlist_tracks":0}""");

        assertTrue(LmsPlayerActivity.parse("p", status).isEmpty());
    }
}
