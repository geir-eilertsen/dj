package dj.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.adapter.sqlite.SqliteArtistProfileStore;
import dj.adapter.sqlite.SqliteMoodStore;
import dj.adapter.stub.NoMoodInterpreter;
import dj.domain.MoodService;
import dj.domain.PhotoMoods;
import dj.domain.model.PhotoReading;
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
import java.util.ArrayList;
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
    private MoodService moods;

    @BeforeEach
    void start() throws Exception {
        List<Track> library = List.of(new Track(TrackId.of("1"), "spotify:track:1", "Sommer", "Kim Larsen", "", 1983, 0));
        moods = new MoodService(new SqliteMoodStore(dir.resolve("mood.db")), new NoMoodInterpreter(),
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
    void forgetsARecentMood() throws Exception {
        send(HttpRequest.newBuilder(uri("/mood?player=" + PLAYER + "&text=Kim+Larsen")).POST(HttpRequest.BodyPublishers.noBody()));

        HttpResponse<String> forgot = send(HttpRequest.newBuilder(uri("/mood/forget"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("player=" + PLAYER + "&text=kim+larsen")));

        assertEquals(200, forgot.statusCode(), forgot.body());
        assertEquals(0, JSON.readTree(forgot.body()).path("recent").size());
        JsonNode state = JSON.readTree(send(HttpRequest.newBuilder(uri("/mood?player=" + PLAYER)).GET()).body());
        assertEquals(0, state.path("recent").size());
        assertEquals("Kim Larsen", state.path("current").path("text").asText());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/mood/forget?player=" + PLAYER))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/mood/forget?player=" + PLAYER)).GET()).statusCode());
    }

    @Test
    void rejectsAnEmptyMoodOrAMissingPlayer() throws Exception {
        assertEquals(400, send(HttpRequest.newBuilder(uri("/mood?player=p&text=%20"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/mood")).GET()).statusCode());
    }

    @Test
    void setsTheMoodPhotosShow() throws Exception {
        List<Integer> counts = new ArrayList<>();
        server.enablePhotoMoods(new PhotoMoods((photos, time) -> {
            counts.add(photos.size());
            return new PhotoReading("Kim Larsen for a quiet morning", "A cup of coffee by the window.");
        }, moods, Clock.systemUTC()));

        HttpResponse<String> read = postPhotos("""
                {"player":"aa:bb","photos":["data:image/jpeg;base64,AQID","data:image/png;base64,BAU="]}""");

        assertEquals(200, read.statusCode(), read.body());
        JsonNode answer = JSON.readTree(read.body());
        assertEquals("Kim Larsen for a quiet morning", answer.path("mood").path("text").asText());
        assertEquals("Kim Larsen", answer.path("mood").path("artists").path(0).asText());
        assertEquals("A cup of coffee by the window.", answer.path("why").asText());
        assertEquals(List.of(2), counts);
        assertEquals("Kim Larsen for a quiet morning", moods.current("aa:bb").orElseThrow().text());
    }

    @Test
    void rejectsPhotoRequestsItCannotRead() throws Exception {
        assertEquals(503, postPhotos("""
                {"player":"p","photos":["AQID"]}""").statusCode());

        server.enablePhotoMoods(new PhotoMoods((photos, time) -> new PhotoReading("calm", ""), moods, Clock.systemUTC()));
        assertEquals(400, postPhotos("""
                {"player":"p","photos":[]}""").statusCode());
        assertEquals(400, postPhotos("""
                {"photos":["AQID"]}""").statusCode());
        assertEquals(400, postPhotos("""
                {"player":"p","photos":["data:text/html;base64,AQID"]}""").statusCode());
        assertEquals(400, postPhotos("not json").statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(uri("/mood/photo")).GET()).statusCode());
    }

    private HttpResponse<String> postPhotos(String body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/mood/photo"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
