package dj.adapter.lastfm;

import com.sun.net.httpserver.HttpServer;
import dj.domain.model.ArtistProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the adapter against a fake Last.fm that answers per {@code method}. */
class LastFmArtistInfoTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private HttpServer fake;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private volatile String lastQuery;

    @BeforeEach
    void start() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/2.0/", exchange -> {
            lastQuery = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String method = lastQuery.replaceAll(".*method=([^&]+).*", "$1");
            byte[] body = responses.getOrDefault(method, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(method, 200), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        fake.start();
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    @Test
    void combinesSimilarArtistsAndTags() {
        responses.put("artist.getsimilar", """
                {"similarartists":{"artist":[{"name":"Katatonia","match":"1"},{"name":"Porcupine Tree","match":"0.62"}],
                 "@attr":{"artist":"Opeth"}}}""");
        responses.put("artist.gettoptags", """
                {"toptags":{"tag":[{"count":100,"name":"Progressive Metal"},{"count":40,"name":"seen live"},
                 {"count":30,"name":"Opeth & Friends"},{"count":25,"name":"death metal"},{"count":2,"name":"swedish"}]}}""");

        ArtistProfile profile = source().fetch("Opeth & Friends").orElseThrow();

        assertEquals(new ArtistProfile("Opeth & Friends",
                List.of(new ArtistProfile.Tag("progressive metal", 1.0), new ArtistProfile.Tag("death metal", 0.25)),
                List.of(new ArtistProfile.SimilarArtist("Katatonia", 1.0), new ArtistProfile.SimilarArtist("Porcupine Tree", 0.62)),
                NOW), profile);
        assertTrue(lastQuery.contains("artist=Opeth & Friends"), lastQuery);
        assertTrue(lastQuery.contains("api_key=test-key"), lastQuery);
        assertTrue(lastQuery.contains("autocorrect=1"), lastQuery);
    }

    @Test
    void acceptsASingleObjectInsteadOfAnArray() {
        responses.put("artist.getsimilar", """
                {"similarartists":{"artist":{"name":"Katatonia","match":"0.5"}}}""");
        responses.put("artist.gettoptags", """
                {"toptags":{"tag":{"count":80,"name":"doom metal"}}}""");

        ArtistProfile profile = source().fetch("Opeth").orElseThrow();

        assertEquals(1, profile.similar().size());
        assertEquals(1, profile.tags().size());
    }

    @Test
    void readsTopTracks() {
        responses.put("artist.gettoptracks", """
                {"toptracks":{"track":[{"name":"Bag Duggede Ruder","playcount":"100"},{"name":"Rejsen til Rom"},
                 {"name":"  "},{"name":"De Første Kærester På Månen"}]}}""");

        assertEquals(List.of("Bag Duggede Ruder", "Rejsen til Rom"), source().topTracks("TV-2", 2));
        assertTrue(lastQuery.contains("method=artist.gettoptracks"), lastQuery);
    }

    @Test
    void hasNoTopTracksForUnknownArtists() {
        responses.put("artist.gettoptracks", """
                {"error":6,"message":"The artist you supplied could not be found"}""");

        assertEquals(List.of(), source().topTracks("Nobody", 5));
    }

    @Test
    void treatsUnknownArtistsAsEmpty() {
        responses.put("artist.getsimilar", """
                {"error":6,"message":"The artist you supplied could not be found"}""");
        statuses.put("artist.getsimilar", 404);

        assertTrue(source().fetch("Nobody").isEmpty());
    }

    @Test
    void failsOnOtherErrors() {
        responses.put("artist.getsimilar", """
                {"error":10,"message":"Invalid API key"}""");
        statuses.put("artist.getsimilar", 403);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> source().fetch("Opeth"));
        assertTrue(e.getMessage().contains("Invalid API key"));
    }

    @Test
    void failsOnServerErrors() {
        statuses.put("artist.getsimilar", 503);
        responses.put("artist.getsimilar", "<html>Service unavailable</html>");

        assertThrows(IllegalStateException.class, () -> source().fetch("Opeth"));
    }

    private LastFmArtistInfo source() {
        URI base = URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/2.0/");
        return new LastFmArtistInfo(HttpClient.newHttpClient(), base, "test-key", Duration.ZERO,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
