package dj.adapter.folken;

import com.sun.net.httpserver.HttpServer;
import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the adapter against the programme page trimmed from the real site (October 2026). */
class FolkenConcertsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-14");
    private static final URI PAGE = URI.create("https://www.folken.no/folken/");

    private HttpServer fake;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String programme;

    @BeforeEach
    void start() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/folken/programme.html")) {
            programme = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/folken/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = programme.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fake.start();
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    private static List<FolkenConcerts.Show> shows() throws IOException {
        try (InputStream in = FolkenConcertsTest.class.getResourceAsStream("/folken/programme.html")) {
            return FolkenConcerts.parseProgramme(new String(in.readAllBytes(), StandardCharsets.UTF_8), PAGE, TODAY);
        }
    }

    @Test
    void readsTheConcertsLeavingOutOtherShowsAndCancelledOnes() throws IOException {
        List<FolkenConcerts.Show> shows = shows();

        assertEquals(List.of("Amaranthe (S) + support: Self Deception (S) + Ankor (ES)", "Salmer på pøbb",
                        "Skoddajazz #4: Bliss Quintet", "Gåte", "Natasha Barrett (NO/UK)", "The Impossible Green + support: Palmer",
                        "Kompisane te Leif", "Seigmen", "KARPE (DIEM) OVERTIME/OVERKILL (2000-2028)",
                        "KARPE (DIEM) OVERTIME/OVERKILL (2000-2028)"),
                shows.stream().map(s -> s.concert().title()).toList());

        Concert gaate = shows.get(3).concert();
        assertEquals(LocalDate.parse("2026-11-06"), gaate.date());
        assertEquals(LocalTime.of(21, 0), gaate.time());
        assertEquals("Folken", gaate.venue());
        assertEquals("Stavanger", gaate.city());
        assertEquals("NO", gaate.countryCode());
        assertEquals("https://www.folken.no/folken/shows/16789-gaate", gaate.url());

        assertNull(shows.get(1).concert().time());
        assertEquals(LocalDate.parse("2028-03-31"), shows.get(8).concert().date());
    }

    @Test
    void findsEveryoneOnTheBill() throws IOException {
        List<FolkenConcerts.Show> shows = shows();

        assertTrue(shows.getFirst().lineup().containsAll(Set.of("amaranthe", "self deception", "ankor")));
        assertTrue(shows.get(2).lineup().contains(ArtistNames.key("Bliss Quintet")));
        assertTrue(shows.get(4).lineup().contains(ArtistNames.key("Natasha Barrett")));
        assertTrue(shows.get(5).lineup().containsAll(Set.of(ArtistNames.key("The Impossible Green"), "palmer")));
        assertTrue(shows.get(8).lineup().contains("karpe"));
    }

    @Test
    void cleansSalesNotesOffTheTitle() {
        assertEquals("Seigmen", FolkenConcerts.bill("Seigmen - FÅ BILLETTER!"));
        assertEquals("Kompisane te Leif", FolkenConcerts.bill("UTSOLGT! Kompisane te Leif"));
        assertEquals("Winterstø (duo) + support: Sivert Moe", FolkenConcerts.bill("Winterstø (duo) + support: Sivert Moe"));
        assertEquals(Set.of(ArtistNames.key("Winterstø (duo)"), ArtistNames.key("Winterstø"), ArtistNames.key("Sivert Moe")),
                FolkenConcerts.lineup("Winterstø (duo) + support: Sivert Moe"));
    }

    @Test
    void givesADateWithoutAYearTheComingOne() {
        assertEquals(Optional.of(LocalDate.parse("2026-11-06")), FolkenConcerts.parseDate("6 November", TODAY));
        assertEquals(Optional.of(LocalDate.parse("2027-01-06")), FolkenConcerts.parseDate("6 januar", LocalDate.parse("2026-12-20")));
        // Just past, as on a programme not yet updated: still this year's
        assertEquals(Optional.of(LocalDate.parse("2026-10-01")), FolkenConcerts.parseDate("1 October", TODAY));
        assertEquals(Optional.of(LocalDate.parse("2027-02-05")), FolkenConcerts.parseDate("5 February 2027", TODAY));
        assertEquals(Optional.empty(), FolkenConcerts.parseDate("31 February", TODAY));
        assertEquals(Optional.empty(), FolkenConcerts.parseDate("Snart", TODAY));
    }

    @Test
    void readsThePageOncePerMaxAgeAndMatchesArtists() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Oslo"));
        FolkenConcerts folken = new FolkenConcerts(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/folken/"), Duration.ofHours(24), clock);

        List<Concert> gaate = folken.upcoming("GÅTE");
        List<Concert> palmer = folken.upcoming("Palmer");

        assertEquals(1, gaate.size());
        assertEquals("GÅTE", gaate.getFirst().artist());
        assertEquals(List.of(LocalDate.parse("2026-11-14")), palmer.stream().map(Concert::date).toList());
        assertEquals(List.of(), folken.upcoming("Fordrekord"));
        assertEquals(1, requests.get());
    }

    @Test
    void aPageWithoutShowsIsAnError() {
        assertThrows(IllegalStateException.class, () -> FolkenConcerts.parseProgramme("<html>Under maintenance</html>", PAGE, TODAY));
    }
}
