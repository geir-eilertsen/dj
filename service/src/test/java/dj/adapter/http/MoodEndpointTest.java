package dj.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.adapter.sqlite.SqliteArtistProfileStore;
import dj.adapter.sqlite.SqliteMoodStore;
import dj.adapter.stub.NoMoodInterpreter;
import dj.domain.MoodService;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /mood over real HTTP, with keyword understanding (no LLM). */
class MoodEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLAYER = "b0%3A3c%3Adc%3A72%3Af2%3A72";

    @TempDir
    Path dir;

    private MixHttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        List<Track> library = List.of(new Track(TrackId.of("1"), "spotify:track:1", "Sommer", "Kim Larsen", "", 1983, 0));
        MoodService moods = new MoodService(new SqliteMoodStore(dir.resolve("mood.db")), new NoMoodInterpreter(),
                new SqliteArtistProfileStore(dir.resolve("dj.db")), () -> library, Clock.systemUTC(),
                Executors.newVirtualThreadPerTaskExecutor(), Duration.ofSeconds(1));
        server = new MixHttpServer(null, null, moods, 0, Map::of);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void setsReadsAndClearsAMood() throws Exception {
        HttpResponse<String> set = send(HttpRequest.newBuilder(uri("/mood"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("player=" + PLAYER + "&text=Kim+Larsen+fra+80-tallet")));
        assertEquals(200, set.statusCode(), set.body());
        JsonNode mood = JSON.readTree(set.body());
        assertEquals("Kim Larsen fra 80-tallet", mood.path("text").asText());
        assertEquals("Kim Larsen", mood.path("artists").path(0).asText());
        assertEquals(1980, mood.path("fromYear").asInt());

        JsonNode state = JSON.readTree(send(HttpRequest.newBuilder(uri("/mood?player=" + PLAYER)).GET()).body());
        assertEquals("Kim Larsen fra 80-tallet", state.path("current").path("text").asText());
        assertEquals("Kim Larsen fra 80-tallet", state.path("recent").path(0).asText());

        assertEquals(200, send(HttpRequest.newBuilder(uri("/mood/clear?player=" + PLAYER))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        state = JSON.readTree(send(HttpRequest.newBuilder(uri("/mood?player=" + PLAYER)).GET()).body());
        assertTrue(state.path("current").isNull());
        assertEquals(1, state.path("recent").size());
    }

    @Test
    void rejectsAnEmptyMoodOrAMissingPlayer() throws Exception {
        assertEquals(400, send(HttpRequest.newBuilder(uri("/mood?player=p&text=%20"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/mood")).GET()).statusCode());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
