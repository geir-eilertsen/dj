package dj.adapter.http;

import dj.domain.model.MixRequest;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MixHttpServerTest {

    @Test
    void parsesPlayerSeedsAndCount() {
        MixRequest request = MixHttpServer.parse("player=b0%3A3c%3Adc%3A72%3Af2%3A72&seed=12,34&count=3");

        assertEquals("b0:3c:dc:72:f2:72", request.playerId());
        assertEquals(List.of(TrackId.of("12"), TrackId.of("34")), request.seeds());
        assertEquals(3, request.count());
    }

    @Test
    void acceptsRepeatedSeedParametersAndEmptySeeds() {
        MixRequest request = MixHttpServer.parse("player=p&seed=1&seed=&seed=2,,3");

        assertEquals(List.of(TrackId.of("1"), TrackId.of("2"), TrackId.of("3")), request.seeds());
        assertEquals(MixHttpServer.DEFAULT_COUNT, request.count());
    }

    @Test
    void rejectsMissingPlayer() {
        assertThrows(IllegalArgumentException.class, () -> MixHttpServer.parse("seed=1"));
        assertThrows(IllegalArgumentException.class, () -> MixHttpServer.parse(null));
    }

    @Test
    void rejectsBadCounts() {
        assertThrows(IllegalArgumentException.class, () -> MixHttpServer.parse("player=p&count=lots"));
        assertThrows(IllegalArgumentException.class, () -> MixHttpServer.parse("player=p&count=0"));
        assertThrows(IllegalArgumentException.class, () -> MixHttpServer.parse("player=p&count=500"));
    }
}
