package dj.adapter.lms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.LibraryUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs the adapter against a fake LMS that serves {@code titles} in pages. */
class LmsLibraryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIBRARY_SIZE = 1203; // more than two pages of 500

    private HttpServer fakeLms;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean lmsDown = new AtomicBoolean();
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void startFakeLms() throws IOException {
        fakeLms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLms.createContext("/jsonrpc.js", exchange -> {
            requests.incrementAndGet();
            if (lmsDown.get()) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            JsonNode command = JSON.readTree(exchange.getRequestBody()).path("params").path(1);
            int offset = command.path(1).asInt();
            int size = command.path(2).asInt();
            StringBuilder loop = new StringBuilder();
            for (int i = offset; i < Math.min(offset + size, LIBRARY_SIZE); i++) {
                loop.append(loop.isEmpty() ? "" : ",")
                        .append("{\"id\":").append(i + 1)
                        .append(",\"title\":\"T").append(i + 1)
                        .append("\",\"artist\":\"A\",\"album\":\"B\",\"year\":\"1997\",\"playcount\":\"").append(i + 1)
                        .append("\",\"url\":\"spotify:track:").append(i + 1).append("\"}");
            }
            byte[] body = ("{\"result\":{\"count\":" + LIBRARY_SIZE + ",\"titles_loop\":[" + loop + "]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fakeLms.start();
    }

    @AfterEach
    void stopFakeLms() {
        fakeLms.stop(0);
    }

    @Test
    void loadsEveryPage() {
        List<Track> tracks = library().allTracks();

        assertEquals(LIBRARY_SIZE, tracks.size());
        assertEquals(LIBRARY_SIZE, tracks.stream().map(Track::id).distinct().count());
        assertEquals(3, requests.get());
    }

    @Test
    void readsTrackFields() {
        Track track = library().allTracks().get(6);

        assertEquals(new Track(TrackId.of("7"), "spotify:track:7", "T7", "A", "B", 1997, 7), track);
    }

    @Test
    void reusesTheSnapshotUntilItExpires() {
        LmsLibrary library = library();

        library.allTracks();
        library.allTracks();
        assertEquals(3, requests.get());

        clock.advance(Duration.ofMinutes(16));
        library.allTracks();
        assertEquals(6, requests.get());
    }

    @Test
    void keepsTheStaleSnapshotWhenLmsGoesDown() {
        LmsLibrary library = library();
        library.refresh();

        lmsDown.set(true);
        clock.advance(Duration.ofMinutes(16));

        assertEquals(LIBRARY_SIZE, library.allTracks().size());
    }

    @Test
    void failsWhenLmsIsDownAndNothingIsCached() {
        lmsDown.set(true);

        assertThrows(LibraryUnavailableException.class, () -> library().allTracks());
    }

    private LmsLibrary library() {
        URI url = URI.create("http://127.0.0.1:" + fakeLms.getAddress().getPort());
        return new LmsLibrary(new LmsJsonRpcClient(url, Duration.ofSeconds(2)), Duration.ofMinutes(15), clock);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
