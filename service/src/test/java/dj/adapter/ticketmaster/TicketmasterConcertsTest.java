package dj.adapter.ticketmaster;

import com.sun.net.httpserver.HttpServer;
import dj.domain.model.Concert;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the adapter against a fake Discovery API that answers per path. */
class TicketmasterConcertsTest {

    private HttpServer fake;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/discovery/v2/", exchange -> {
            String path = exchange.getRequestURI().getPath().replace("/discovery/v2/", "");
            queries.add(path + "?" + URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] body = responses.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
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

    private TicketmasterConcerts source() {
        return new TicketmasterConcerts(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/discovery/v2/"),
                "test-key", Duration.ZERO, Clock.systemUTC());
    }

    @Test
    void listsTheEventsOfTheAttractionWithTheArtistsName() {
        responses.put("attractions.json", """
                {"_embedded":{"attractions":[
                  {"id":"tribute","name":"Opeth Tribute","upcomingEvents":{"_total":9}},
                  {"id":"small","name":"OPETH","upcomingEvents":{"_total":1}},
                  {"id":"K8vZ","name":"Opeth","upcomingEvents":{"_total":3}}]}}""");
        responses.put("events.json", """
                {"_embedded":{"events":[
                  {"name":"Opeth - The Last Will Tour","url":"https://tm/1",
                   "dates":{"start":{"localDate":"2026-11-14","localTime":"20:00:00"},"status":{"code":"onsale"}},
                   "_embedded":{"venues":[{"name":"Sentrum Scene","city":{"name":"Oslo"},"country":{"countryCode":"NO"}}]}},
                  {"name":"Opeth - VIP Package","url":"https://tm/2",
                   "dates":{"start":{"localDate":"2026-11-14"},"status":{"code":"onsale"}},
                   "_embedded":{"venues":[{"name":"Sentrum Scene","city":{"name":"Oslo"},"country":{"countryCode":"NO"}}]}},
                  {"name":"Cancelled show","dates":{"start":{"localDate":"2026-11-15"},"status":{"code":"cancelled"}},
                   "_embedded":{"venues":[{"name":"Annexet","city":{"name":"Stockholm"},"country":{"countryCode":"SE"}}]}},
                  {"name":"Date to be announced","dates":{"start":{"dateTBA":true},"status":{"code":"onsale"}}},
                  {"name":"Opeth","dates":{"start":{"localDate":"2026-12-01"},"status":{"code":"rescheduled"}},
                   "_embedded":{"venues":[{"name":"Roundhouse","city":{"name":"London"},"country":{"countryCode":"GB"}}]}}]}}""");

        List<Concert> concerts = source().upcoming("Opeth");

        assertEquals(List.of(
                new Concert("Opeth", "Opeth - The Last Will Tour", LocalDate.parse("2026-11-14"), LocalTime.of(20, 0),
                        "Sentrum Scene", "Oslo", "NO", "https://tm/1"),
                new Concert("Opeth", "Opeth", LocalDate.parse("2026-12-01"), null, "Roundhouse", "London", "GB", "")),
                concerts);
        assertTrue(queries.get(0).contains("keyword=Opeth"), queries.get(0));
        assertTrue(queries.get(0).contains("apikey=test-key"), queries.get(0));
        assertTrue(queries.get(1).contains("attractionId=K8vZ"), queries.get(1));
    }

    @Test
    void doesNotAskForEventsOfAnUnknownOrIdleArtist() {
        responses.put("attractions.json", """
                {"_embedded":{"attractions":[{"id":"x","name":"Gasolin'","upcomingEvents":{"_total":0}}]}}""");
        assertEquals(List.of(), source().upcoming("Gasolin'"));
        responses.put("attractions.json", "{\"page\":{\"totalElements\":0}}");
        assertEquals(List.of(), source().upcoming("Nobody"));
        assertEquals(2, queries.size());
    }

    @Test
    void failsWithoutLeakingTheKey() {
        statuses.put("attractions.json", 401);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> source().upcoming("Opeth"));
        assertTrue(e.getMessage().contains("401"));
        assertFalse(e.getMessage().contains("test-key"));
    }
}
