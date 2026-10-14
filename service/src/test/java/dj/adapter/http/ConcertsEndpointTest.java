package dj.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dj.adapter.sqlite.SqliteConcertStore;
import dj.adapter.sqlite.SqliteDiscoveryStore;
import dj.domain.ConcertFinder;
import dj.domain.ConcertProviders;
import dj.domain.model.Concert;
import dj.domain.model.ConcertProvider;
import dj.domain.port.ConcertSource;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /concerts over real HTTP. */
class ConcertsEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private MixHttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final CountDownLatch slow = new CountDownLatch(1);
    private final AtomicInteger changes = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = new MixHttpServer(null, null, null, 0, Map::of);
        server.start();
    }

    @AfterEach
    void stop() {
        slow.countDown();
        server.stop();
    }

    private void enable() {
        LocalDate soon = LocalDate.now().plusDays(30);
        ConcertSource source = new ConcertSource() {
            @Override
            public ConcertProvider provider() {
                return new ConcertProvider("test", "Test", "Everywhere", true);
            }

            @Override
            public List<Concert> upcoming(String artist) {
                return lookUp(artist, soon);
            }
        };
        SqliteConcertStore store = new SqliteConcertStore(dir.resolve("concerts.db"));
        ConcertProviders providers = new ConcertProviders(store).add(source)
                .addUnavailable(new ConcertProvider("keyed", "Keyed", "Worldwide", true), "needs KEYED_API_KEY");
        ConcertFinder finder = new ConcertFinder(List::of, new SqliteDiscoveryStore(dir.resolve("discovery.db")), providers,
                store, Clock.systemDefaultZone(), Duration.ofHours(24));
        server.enableConcerts(finder, Duration.ofMillis(300), changes::incrementAndGet);
    }

    private List<Concert> lookUp(String artist, LocalDate soon) {
        if (artist.equals("Slow")) {
            try {
                slow.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Released at teardown: don't write to the temp dir while it's being deleted
            throw new IllegalStateException("too slow");
        }
        return artist.equals("Opeth")
                ? List.of(new Concert("x", "Tour", soon, LocalTime.of(20, 0), "Sentrum Scene", "Oslo", "NO", ""))
                : List.of();
    }

    @Test
    void saysSoWhenConcertsAreNotSetUp() throws Exception {
        assertEquals(404, get("/concerts?artist=Opeth").statusCode());
    }

    @Test
    void looksUpAnArtistAndListsEveryoneWithConcerts() throws Exception {
        enable();
        HttpResponse<String> one = get("/concerts?artist=Opeth");
        assertEquals(200, one.statusCode(), one.body());
        JsonNode concert = JSON.readTree(one.body()).path("concerts").path(0);
        assertEquals(LocalDate.now().plusDays(30).toString(), concert.path("date").asText());
        assertEquals("20:00", concert.path("time").asText());
        assertEquals("Oslo", concert.path("city").asText());
        assertEquals("NO", concert.path("country").asText());
        assertFalse(concert.has("url"));
        get("/concerts?artist=Tool");

        JsonNode all = JSON.readTree(get("/concerts").body());
        assertEquals(1, all.path("artists").size());
        assertEquals("Opeth", all.path("artists").path(0).path("artist").asText());
    }

    @Test
    void givesUpOnASlowLookup() throws Exception {
        enable();
        assertEquals(504, get("/concerts?artist=Slow").statusCode());
    }

    @Test
    void listsAndChoosesProviders() throws Exception {
        enable();
        JsonNode listed = JSON.readTree(get("/concerts/providers").body()).path("providers");
        assertEquals(2, listed.size());
        assertEquals("test", listed.path(0).path("id").asText());
        assertEquals("Everywhere", listed.path(0).path("coverage").asText());
        assertTrue(listed.path(0).path("enabled").asBoolean());
        assertFalse(listed.path(0).has("unavailable"));
        assertFalse(listed.path(1).path("enabled").asBoolean());
        assertEquals("needs KEYED_API_KEY", listed.path(1).path("unavailable").asText());

        HttpResponse<String> none = post("/concerts/providers", "enabled=");
        assertEquals(200, none.statusCode(), none.body());
        assertFalse(JSON.readTree(none.body()).path("providers").path(0).path("enabled").asBoolean());
        assertEquals(1, changes.get());
        assertEquals(0, JSON.readTree(get("/concerts?artist=Opeth").body()).path("concerts").size());

        assertTrue(JSON.readTree(post("/concerts/providers", "enabled=test%2Ckeyed").body())
                .path("providers").path(0).path("enabled").asBoolean());
        assertEquals(400, post("/concerts/providers", "enabled=bandsintown").statusCode());
        assertEquals(400, post("/concerts/providers", "other=1").statusCode());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
