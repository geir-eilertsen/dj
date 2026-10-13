package dj.adapter.madnesslive;

import com.sun.net.httpserver.HttpServer;
import dj.domain.model.Concert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the adapter against a fake shop serving pages trimmed from the real site (October 2026). */
class MadnessLiveConcertsTest {

    private HttpServer fake;
    private String origin;
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            requests.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            String body = pages.get(exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query));
            byte[] bytes = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fake.start();
        origin = "http://127.0.0.1:" + fake.getAddress().getPort();

        pages.put("/es/12-entradas", "<div>"
                + link("conciertos-en-barcelona/1998-comprar-entrada-amorphis-insomnium-unto-others-barcelona")
                + link("conciertos-en-barcelona/1998-comprar-entrada-amorphis-insomnium-unto-others-barcelona")
                + link("conciertos-en-madrid/2010-comprar-entrada-epica-madrid")
                + "<a href=\"" + origin + "/es/madnesszine/41_resena-soen-memorial.html\">zine</a>"
                + "<a rel=\"next\" href=\"" + origin + "/es/12-entradas?page=2\" class=\"next\">Siguiente</a></div>");
        pages.put("/es/12-entradas?page=2", link("conciertos-en-pais-vasco/1999-comprar-entrada-wucan-portugalete")
                + link("conciertos-en-barcelona/2001-comprar-entrada-enslaved-barcelona"));
        pages.put("/es/conciertos-en-barcelona/1998-comprar-entrada-amorphis-insomnium-unto-others-barcelona.html", show(
                "Amorphis + Insomnium + Unto Others (Barcelona)",
                "<p><em>Martes 9 de febrero 2027</em><br /><strong>Sala Apolo </strong><span class=\"LrzXr\">(Carrer Nou de la Rambla, 113</span>)<br />Puertas: 17:30 hrs</p>",
                "Amorphis"));
        pages.put("/es/conciertos-en-madrid/2010-comprar-entrada-epica-madrid.html", show(
                "Epica The Symphonic Synergy (Madrid)",
                "<p>Domingo 10 de octubre de 2027<br />Palacio Vistalegre (Calle de Matilde Hern&aacute;ndez, s/n)<br />Puertas: 19:00</p>",
                "Epica"));
        pages.put("/es/conciertos-en-pais-vasco/1999-comprar-entrada-wucan-portugalete.html", show(
                "CANCELADO: Wucan (Portugalete)",
                "<p>Jueves 15 de octubre 2026<br />Groove (Ballonti Auzoa, 23)</p>", "Wucan"));
        pages.put("/es/conciertos-en-barcelona/2001-comprar-entrada-enslaved-barcelona.html", show(
                "Enslaved + S&oacute;lstafir + Gaahl&#8217;S Wyrd (Barcelona)",
                "<p>Domingo 14 de febrero 2027<br /><strong>Razzmatazz 2&nbsp;\u00a0</strong>(Carrer Pamplona 88)</p>", "Hippotraktor"));
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    private String link(String path) {
        return "<a href=\"" + origin + "/es/" + path + ".html\" class=\"thumbnail\">x</a>";
    }

    private static String show(String title, String description, String band) {
        return "<h1 class=\"h1 page-title\"><span>" + title + "</span></h1>"
                + "<div id=\"product-description-short-1998\" class=\"rte-content product-description\">" + description
                + "<p style=\"font-size:10px;\">* Los horarios y/o ubicacion pueden sufrir modificaciones</p></div>"
                + "<dl><dt class=\"name\">Ciudad</dt>\n<dd class=\"value\">Barcelona</dd>"
                + "<dt class=\"name\">Banda</dt>\n<dd class=\"value\">" + band + "</dd></dl>";
    }

    private MadnessLiveConcerts source(Clock clock) {
        return new MadnessLiveConcerts(HttpClient.newHttpClient(), URI.create(origin + "/es/12-entradas"),
                Duration.ZERO, Duration.ofHours(24), clock);
    }

    @Test
    void findsAnArtistAnywhereOnTheBill() {
        MadnessLiveConcerts source = source(Clock.systemUTC());

        assertEquals(List.of(new Concert("Insomnium", "Amorphis + Insomnium + Unto Others", LocalDate.parse("2027-02-09"),
                        null, "Sala Apolo", "Barcelona", "ES",
                        origin + "/es/conciertos-en-barcelona/1998-comprar-entrada-amorphis-insomnium-unto-others-barcelona.html")),
                source.upcoming("Insomnium"));
        // The headliner named in the "Banda" feature, when the title is a show name
        assertEquals("Palacio Vistalegre", source.upcoming("EPICA").getFirst().venue());
        assertEquals(LocalDate.parse("2027-10-10"), source.upcoming("Epica").getFirst().date());
        assertEquals("Razzmatazz 2", source.upcoming("Sólstafir").getFirst().venue());
        assertEquals(1, source.upcoming("Enslaved").size());
        assertEquals(List.of(), source.upcoming("Wucan"));
        assertEquals(List.of(), source.upcoming("Opeth"));
    }

    @Test
    void crawlsOncePerMaxAge() {
        Instant start = Instant.parse("2026-10-13T12:00:00Z");
        MadnessLiveConcerts source = source(Clock.fixed(start, ZoneOffset.UTC));
        source.upcoming("Amorphis");
        int crawl = requests.get();
        source.upcoming("Epica");
        assertEquals(crawl, requests.get());
        assertEquals(6, crawl, "two listing pages and four distinct shows");
    }

    @Test
    void keepsTheLastCatalogueWhenTheSiteFailsAndWaitsBeforeRetrying() {
        Instant[] now = {Instant.parse("2026-10-13T12:00:00Z")};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now[0]; }
        };
        MadnessLiveConcerts source = source(clock);
        assertEquals(1, source.upcoming("Amorphis").size());

        status = 503;
        now[0] = now[0].plus(Duration.ofHours(25));
        assertEquals(1, source.upcoming("Amorphis").size());
        int afterFailure = requests.get();
        assertEquals(1, source.upcoming("Epica").size());
        assertEquals(afterFailure, requests.get(), "no retry within 10 minutes");
    }

    @Test
    void failsWhenTheSiteIsDownAndNothingIsKnown() {
        status = 503;
        MadnessLiveConcerts source = source(Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> source.upcoming("Amorphis"));
        int requestsSoFar = requests.get();
        assertThrows(IllegalStateException.class, () -> source.upcoming("Epica"));
        assertEquals(requestsSoFar, requests.get());
    }

    @Test
    void readsSpanishDates() {
        assertEquals(Optional.of(LocalDate.of(2026, 11, 6)), MadnessLiveConcerts.parseDate("Viernes 6 de noviembre de 2026"));
        assertEquals(Optional.of(LocalDate.of(2027, 7, 4)), MadnessLiveConcerts.parseDate("2, 3 & 4 Julio 2027"));
        assertEquals(Optional.of(LocalDate.of(2027, 9, 25)), MadnessLiveConcerts.parseDate("24 y 25 de septiembre 2027"));
        assertEquals(Optional.empty(), MadnessLiveConcerts.parseDate("Próximamente"));
        assertTrue(MadnessLiveConcerts.parseShow("<html>no show</html>", "x").isEmpty());
    }
}
