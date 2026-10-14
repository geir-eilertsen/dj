package dj.adapter.madnesslive;

import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.model.ConcertProvider;
import dj.adapter.web.Crawled;
import dj.adapter.web.Html;
import dj.adapter.web.PoliteHttp;
import dj.domain.port.ConcertSource;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concerts sold by Madness Live!, a Spanish rock and metal promoter whose shows aren't on
 * Ticketmaster. There is no API: the ticket shop (PrestaShop) lists every show, and each show's
 * page has its line-up in the title ("Amorphis + Insomnium + Unto Others (Barcelona)"), the date
 * and venue in the short description ("Martes 9 de febrero 2027", "Sala Apolo (address)") and
 * the headliner as the "Banda" feature.
 *
 * <p>The whole catalogue is crawled at most once per {@code maxAge}, one page per
 * {@code minInterval}, and an artist matches any show they're on the line-up of, support acts too.
 */
public class MadnessLiveConcerts implements ConcertSource {

    public static final ConcertProvider PROVIDER = new ConcertProvider("madness-live", "Madness Live!",
            "Rock and metal in Spain and Portugal", false);
    public static final URI DEFAULT_LISTING = URI.create("https://www.madnesslive.es/es/12-entradas");

    private static final Logger LOG = System.getLogger(MadnessLiveConcerts.class.getName());
    private static final int MAX_LISTING_PAGES = 10;
    private static final Pattern NEXT_PAGE = Pattern.compile("<a[^>]*rel=\"next\"[^>]*href=\"([^\"]+)\"|"
            + "<a[^>]*href=\"([^\"]+)\"[^>]*rel=\"next\"");
    private static final Pattern TITLE = Pattern.compile("class=\"h1 page-title\"><span>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern DESCRIPTION = Pattern.compile(
            "<div id=\"product-description-short-\\d+\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern FEATURE = Pattern.compile(
            "<dt class=\"name\">(.*?)</dt>\\s*<dd class=\"value\">(.*?)</dd>", Pattern.DOTALL);
    private static final Pattern CITY = Pattern.compile("\\(([^()]+)\\)\\s*$");
    private static final Pattern CANCELLED = Pattern.compile("(?i)^\\s*(cancelado|cancelled|aplazado)\\b");
    /** "9 de febrero 2027", "6 de noviembre de 2026"; festivals ("2, 3 & 4 Julio 2027") give the last day. */
    private static final Pattern DATE = Pattern.compile(
            "(\\d{1,2})\\s+(?:de\\s+)?([a-záéíóúñ]+)\\s+(?:de\\s+)?(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abril", 4),
            Map.entry("mayo", 5), Map.entry("junio", 6), Map.entry("julio", 7), Map.entry("agosto", 8),
            Map.entry("septiembre", 9), Map.entry("setiembre", 9), Map.entry("octubre", 10),
            Map.entry("noviembre", 11), Map.entry("diciembre", 12));
    private static final Set<String> PORTUGUESE_CITIES = Set.of("lisboa", "lisbon", "porto", "oporto", "braga",
            "coimbra", "faro", "aveiro", "setubal");

    /** A show and everyone on its bill, as {@link ArtistNames#key} keys. */
    record Show(Concert concert, Set<String> lineup) {
    }

    private final PoliteHttp http;
    private final URI listing;
    /** Show pages: {@code <origin>/es/conciertos-en-barcelona/1998-comprar-entrada-amorphis-....html} */
    private final Pattern showLink;
    private final Crawled<List<Show>> catalogue;

    public MadnessLiveConcerts(Duration maxAge) {
        this(PoliteHttp.defaultClient(), DEFAULT_LISTING, Duration.ofSeconds(1), maxAge, Clock.systemUTC());
    }

    MadnessLiveConcerts(HttpClient http, URI listing, Duration minInterval, Duration maxAge, Clock clock) {
        this.http = new PoliteHttp(http, "Madness Live", minInterval, clock);
        this.listing = listing;
        this.showLink = Pattern.compile("href=\"(" + Pattern.quote(listing.getScheme() + "://" + listing.getRawAuthority())
                + "/[a-z]{2}/[^/\"]+/\\d+-[^/\"]+\\.html)\"");
        this.catalogue = new Crawled<>("Madness Live", this::crawl, maxAge, clock);
    }

    @Override
    public ConcertProvider provider() {
        return PROVIDER;
    }

    @Override
    public List<Concert> upcoming(String artistName) {
        String key = ArtistNames.key(artistName);
        return catalogue.get().stream()
                .filter(show -> show.lineup().contains(key))
                .map(show -> {
                    Concert c = show.concert();
                    return new Concert(artistName, c.title(), c.date(), c.time(), c.venue(), c.city(), c.countryCode(), c.url());
                })
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .toList();
    }

    private List<Show> crawl() {
        List<Show> found = new ArrayList<>();
        int unreadable = 0;
        for (URI page : showPages()) {
            try {
                Optional<Show> show = parseShow(http.get(page), page.toString());
                if (show.isPresent()) {
                    found.add(show.get());
                } else {
                    unreadable++;
                }
            } catch (RuntimeException e) {
                unreadable++;
                LOG.log(Level.WARNING, "Could not read Madness Live show " + page + ": " + e.getMessage());
            }
        }
        if (found.isEmpty() && unreadable > 0) {
            // Most likely a redesign: keep the old catalogue rather than forget every show
            throw new IllegalStateException("Could not read any of " + unreadable + " Madness Live shows");
        }
        LOG.log(Level.INFO, "Madness Live: {0} shows ({1} cancelled or unreadable)", found.size(), unreadable);
        return List.copyOf(found);
    }

    private List<URI> showPages() {
        Set<URI> pages = new LinkedHashSet<>();
        URI page = listing;
        for (int i = 0; i < MAX_LISTING_PAGES && page != null; i++) {
            String html = http.get(page);
            pages.addAll(parseShowLinks(html));
            page = nextPage(html).map(page::resolve).orElse(null);
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("Madness Live lists no shows at " + listing);
        }
        return List.copyOf(pages);
    }

    private List<URI> parseShowLinks(String html) {
        Set<URI> links = new LinkedHashSet<>();
        Matcher m = showLink.matcher(html);
        while (m.find()) {
            links.add(URI.create(m.group(1)));
        }
        return List.copyOf(links);
    }

    static Optional<String> nextPage(String html) {
        Matcher m = NEXT_PAGE.matcher(html);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(Html.unescape(m.group(1) != null ? m.group(1) : m.group(2)));
    }

    /** @return empty for a cancelled show, a page without a date, or one that isn't a show at all */
    static Optional<Show> parseShow(String html, String url) {
        Matcher title = TITLE.matcher(html);
        Matcher description = DESCRIPTION.matcher(html);
        if (!title.find() || !description.find()) {
            return Optional.empty();
        }
        String heading = Html.text(title.group(1));
        if (CANCELLED.matcher(heading).find()) {
            return Optional.empty();
        }

        List<String> lines = lines(description.group(1).split("<p style=\"font-size:10px;\">")[0]);
        Optional<LocalDate> date = lines.isEmpty() ? Optional.empty() : parseDate(lines.getFirst());
        if (date.isEmpty()) {
            return Optional.empty();
        }
        String venue = lines.size() > 1 ? lines.get(1).replaceAll("\\s*\\(.*$", "").strip() : "";

        String city = "";
        String bill = heading;
        Matcher cityMatch = CITY.matcher(heading);
        if (cityMatch.find()) {
            city = cityMatch.group(1).strip();
            bill = heading.substring(0, cityMatch.start()).strip();
        }

        Set<String> lineup = new LinkedHashSet<>();
        for (String act : bill.split("\\s+\\+\\s+")) {
            lineup.add(ArtistNames.key(act));
        }
        Matcher feature = FEATURE.matcher(html);
        while (feature.find()) {
            if (Html.text(feature.group(1)).equalsIgnoreCase("Banda")) {
                lineup.add(ArtistNames.key(Html.text(feature.group(2))));
            }
        }
        lineup.remove("");

        String country = PORTUGUESE_CITIES.contains(ArtistNames.key(city)) ? "PT" : "ES";
        // Times on the site are door times, sometimes mistyped ("Puertas: 7:00"): leave them out
        return Optional.of(new Show(new Concert("", bill, date.get(), null, venue, city, country, url), lineup));
    }

    static Optional<LocalDate> parseDate(String line) {
        Matcher m = DATE.matcher(line);
        Optional<LocalDate> date = Optional.empty();
        while (m.find()) {
            Integer month = MONTHS.get(m.group(2).toLowerCase());
            if (month != null) {
                try {
                    date = Optional.of(LocalDate.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1))));
                    break;
                } catch (DateTimeException e) {
                    // "31 de febrero": try the next one
                }
            }
        }
        return date;
    }

    /** The visible lines of an HTML fragment, split at {@code <br>} and paragraphs. */
    private static List<String> lines(String html) {
        List<String> lines = new ArrayList<>();
        for (String line : html.split("(?i)<br\\s*/?>|</p>")) {
            String t = Html.text(line);
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        return lines;
    }
}
