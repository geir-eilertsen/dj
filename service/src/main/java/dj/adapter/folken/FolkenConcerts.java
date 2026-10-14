package dj.adapter.folken;

import dj.adapter.web.Crawled;
import dj.adapter.web.Html;
import dj.adapter.web.PoliteHttp;
import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.model.ConcertProvider;
import dj.domain.port.ConcertSource;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concerts at Folken, a club in Stavanger (Norway) that sells its own tickets (through Linticket)
 * and is hardly on Ticketmaster. There is no API: the front page lists the whole programme, each
 * show with its type ({@code data-type='Konsert'}), title, date ("6 November", or "5 February 2027"
 * after this year), time and a link to its page.
 *
 * <p>The page is read at most once per {@code maxAge}. Titles name the bill
 * ("Amaranthe (S) + support: Self Deception (S) + Ankor (ES) - UTSOLGT!", "Skoddajazz #4: Bliss
 * Quintet"), and an artist matches any act on it, support acts too.
 */
public class FolkenConcerts implements ConcertSource {

    public static final ConcertProvider PROVIDER = new ConcertProvider("folken", "Folken",
            "Folken, Stavanger (Norway)", false);
    public static final URI DEFAULT_PROGRAMME = URI.create("https://www.folken.no/folken/");

    private static final Logger LOG = System.getLogger(FolkenConcerts.class.getName());
    private static final ZoneId STAVANGER = ZoneId.of("Europe/Oslo");
    private static final Pattern ITEM = Pattern.compile(
            "<li class=\"list-item\" data-type='([^']*)'>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<div class=\"title\"><a href=\"([^\"]+)\">(.*?)</a></div>", Pattern.DOTALL);
    private static final Pattern INFO = Pattern.compile("<div class=\"info-item\">(.*?)</div>", Pattern.DOTALL);
    /** "6 November", "5 February 2027"; Norwegian month names too, in case the site switches language. */
    private static final Pattern DATE = Pattern.compile("^(\\d{1,2})\\.?\\s+(\\p{L}+)(?:\\s+(\\d{4}))?$");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})[:.](\\d{2})$");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("januar", 1), Map.entry("february", 2), Map.entry("februar", 2),
            Map.entry("march", 3), Map.entry("mars", 3), Map.entry("april", 4), Map.entry("may", 5),
            Map.entry("mai", 5), Map.entry("june", 6), Map.entry("juni", 6), Map.entry("july", 7),
            Map.entry("juli", 7), Map.entry("august", 8), Map.entry("september", 9), Map.entry("october", 10),
            Map.entry("oktober", 10), Map.entry("november", 11), Map.entry("december", 12),
            Map.entry("desember", 12));
    private static final Pattern CANCELLED = Pattern.compile("(?i)\\b(avlyst|cancelled|utsatt|postponed)\\b");
    /** Sales notes that aren't part of anyone's name. */
    private static final Pattern SALES_NOTE = Pattern.compile("(?i)\\b(utsolgt|sold out|få billetter|siste billetter)\\b!?");
    private static final Pattern SUPPORT = Pattern.compile("(?i)^(support|special guests?|gjest(?:er)?|guests?)\\s*:\\s*");
    /** "Skoddajazz #4: Bliss Quintet": a series name before the act. */
    private static final Pattern SERIES = Pattern.compile("^[^:]+:\\s+(.+)$");

    /** A show and everyone on its bill, as {@link ArtistNames#key} keys. */
    record Show(Concert concert, Set<String> lineup) {
    }

    private final PoliteHttp http;
    private final URI programme;
    private final Clock clock;
    private final Crawled<List<Show>> catalogue;

    public FolkenConcerts(Duration maxAge) {
        this(PoliteHttp.defaultClient(), DEFAULT_PROGRAMME, maxAge, Clock.system(STAVANGER));
    }

    /** @param clock also decides which year a date without one is in (Stavanger's today) */
    FolkenConcerts(HttpClient http, URI programme, Duration maxAge, Clock clock) {
        this.http = new PoliteHttp(http, "Folken", Duration.ofSeconds(1), clock);
        this.programme = programme;
        this.clock = clock;
        this.catalogue = new Crawled<>("Folken", this::crawl, maxAge, clock);
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
        List<Show> shows = parseProgramme(http.get(programme), programme, LocalDate.now(clock.withZone(STAVANGER)));
        LOG.log(Level.INFO, "Folken: {0} concerts", shows.size());
        return shows;
    }

    /**
     * The concerts on the programme page, cancelled ones left out.
     *
     * @param today decides the year of a date given without one
     * @throws IllegalStateException if the page lists nothing at all (most likely a redesign), so
     *                               the last good catalogue is kept
     */
    static List<Show> parseProgramme(String html, URI page, LocalDate today) {
        List<Show> shows = new ArrayList<>();
        int items = 0;
        Matcher item = ITEM.matcher(html);
        while (item.find()) {
            items++;
            if (item.group(1).equalsIgnoreCase("Konsert")) {
                parseShow(item.group(2), page, today).ifPresent(shows::add);
            }
        }
        if (items == 0) {
            throw new IllegalStateException("Folken's programme lists no shows at " + page);
        }
        return List.copyOf(shows);
    }

    private static Optional<Show> parseShow(String html, URI page, LocalDate today) {
        Matcher title = TITLE.matcher(html);
        if (!title.find()) {
            return Optional.empty();
        }
        String heading = Html.text(title.group(2));
        if (CANCELLED.matcher(heading).find()) {
            return Optional.empty();
        }
        List<String> infos = new ArrayList<>();
        Matcher info = INFO.matcher(html);
        while (info.find()) {
            infos.add(Html.text(info.group(1)));
        }
        Optional<LocalDate> date = infos.isEmpty() ? Optional.empty() : parseDate(infos.getFirst(), today);
        if (date.isEmpty()) {
            return Optional.empty();
        }
        LocalTime time = infos.size() > 1 ? parseTime(infos.get(1)) : null;

        String bill = bill(heading);
        Set<String> lineup = lineup(bill);
        if (lineup.isEmpty()) {
            return Optional.empty();
        }
        String url = page.resolve(Html.unescape(title.group(1))).toString();
        return Optional.of(new Show(new Concert("", bill, date.get(), time, "Folken", "Stavanger", "NO", url), lineup));
    }

    /** The title without sales notes: "Seigmen - FÅ BILLETTER!" → "Seigmen". */
    static String bill(String heading) {
        String bill = heading.split("\\s+[-–]\\s+")[0];
        return SALES_NOTE.matcher(bill).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    /**
     * Everyone on the bill, as {@link ArtistNames#key} keys: acts split at " + ", without "support:",
     * a series name ("Skoddajazz #4:") or notes in brackets ("Ankor (ES)", "KARPE (DIEM) ...").
     */
    static Set<String> lineup(String bill) {
        Set<String> lineup = new LinkedHashSet<>();
        for (String act : bill.split("\\s+\\+\\s+")) {
            act = SUPPORT.matcher(act.strip()).replaceFirst("");
            List<String> names = new ArrayList<>(List.of(act));
            Matcher series = SERIES.matcher(act);
            if (series.matches()) {
                names.add(series.group(1));
            }
            for (String name : names) {
                lineup.add(ArtistNames.key(name));
                int bracket = name.indexOf('(');
                if (bracket > 0) {
                    lineup.add(ArtistNames.key(name.substring(0, bracket)));
                }
            }
        }
        lineup.remove("");
        return lineup;
    }

    /** "6 November" is this year's, unless that was over a month ago: then next year's. */
    static Optional<LocalDate> parseDate(String text, LocalDate today) {
        Matcher m = DATE.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        Integer month = MONTHS.get(m.group(2).toLowerCase());
        if (month == null) {
            return Optional.empty();
        }
        int day = Integer.parseInt(m.group(1));
        try {
            if (m.group(3) != null) {
                return Optional.of(LocalDate.of(Integer.parseInt(m.group(3)), month, day));
            }
            LocalDate date = LocalDate.of(today.getYear(), month, day);
            return Optional.of(date.isBefore(today.minusMonths(1)) ? date.plusYears(1) : date);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static LocalTime parseTime(String text) {
        Matcher m = TIME.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
