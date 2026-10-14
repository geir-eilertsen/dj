package dj.domain;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.model.Track;
import dj.domain.port.ConcertSource;
import dj.domain.port.ConcertStore;
import dj.domain.port.DiscoveryStore;
import dj.domain.port.LibraryPort;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Finds upcoming concerts in the listener's region (Europe by default) by the artists the listener
 * plays: on demand for the artist that's playing, and in a periodic scan of every library and
 * listened artist.
 *
 * <p>Lookups are cached in the {@link ConcertStore} for {@code maxAge}, including empty ones, so
 * an artist is asked about at most about once per {@code maxAge}.
 */
public class ConcertFinder {

    private static final Logger LOG = System.getLogger(ConcertFinder.class.getName());
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** ISO 3166-1 alpha-2 codes of European countries and territories (not Turkey, Russia or the Caucasus). */
    public static final Set<String> EUROPE = Set.of(
            "AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FO",
            "FR", "GB", "GG", "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE", "LI", "LT", "LU", "LV", "MC",
            "MD", "ME", "MK", "MT", "NL", "NO", "PL", "PT", "RO", "RS", "SE", "SI", "SK", "SM", "UA", "VA", "XK");

    private static final Pattern FEATURING = Pattern.compile("(?i)\\s+(feat\\.?|featuring|ft\\.)\\s.*$");
    private static final Set<String> NOT_ARTISTS = Set.of("", "various artists", "various", "unknown artist");

    public record Result(int artists, int checked, int withConcerts, int failed, boolean aborted) {
    }

    private final LibraryPort library;
    private final DiscoveryStore discovered;
    private final ConcertSource source;
    private final ConcertStore store;
    private final Clock clock;
    private final Duration maxAge;
    private final Set<String> countries;

    /** Concerts in Europe ({@link #EUROPE}). */
    public ConcertFinder(LibraryPort library, DiscoveryStore discovered, ConcertSource source, ConcertStore store,
                         Clock clock, Duration maxAge) {
        this(library, discovered, source, store, clock, maxAge, EUROPE);
    }

    /**
     * @param clock     also gives the time zone that decides which concerts are still upcoming
     * @param countries ISO 3166-1 alpha-2 codes of the countries to keep; empty keeps every country
     */
    public ConcertFinder(LibraryPort library, DiscoveryStore discovered, ConcertSource source, ConcertStore store,
                         Clock clock, Duration maxAge, Set<String> countries) {
        this.library = library;
        this.discovered = discovered;
        this.source = source;
        this.store = store;
        this.clock = clock;
        this.maxAge = maxAge;
        this.countries = Set.copyOf(countries);
    }

    /**
     * The countries named by a setting such as "europe", "europe,TR", "US,CA" or "all": ISO codes
     * and the word "europe", comma-separated. "all" (or nothing) gives an empty set: every country.
     *
     * @throws IllegalArgumentException for anything that's not a two-letter code
     */
    public static Set<String> countries(String setting) {
        Set<String> codes = new TreeSet<>();
        for (String part : setting.split(",")) {
            String code = part.strip().toUpperCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            if (code.equals("ALL")) {
                return Set.of();
            }
            if (code.equals("EUROPE")) {
                codes.addAll(EUROPE);
            } else if (code.matches("[A-Z]{2}")) {
                codes.add(code);
            } else {
                throw new IllegalArgumentException("Not a country code: " + part.strip());
            }
        }
        return codes;
    }

    /**
     * Upcoming concerts in the region by this artist, from the cache if it's fresh.
     *
     * @throws RuntimeException if the source fails and nothing is known about the artist
     */
    public List<Concert> forArtist(String artist) {
        String name = mainArtist(artist);
        if (NOT_ARTISTS.contains(ArtistNames.key(name))) {
            return List.of();
        }
        Optional<ArtistConcerts> cached = store.get(name);
        if (cached.isPresent() && isFresh(cached.get(), maxAge)) {
            return upcoming(cached.get().concerts());
        }
        try {
            return upcoming(lookUp(name).concerts());
        } catch (RuntimeException e) {
            if (cached.isPresent()) {
                LOG.log(Level.WARNING, "Concert lookup for " + name + " failed; using the last one", e);
                return upcoming(cached.get().concerts());
            }
            throw e;
        }
    }

    /** Every known artist with upcoming concerts, the soonest first. */
    public List<ArtistConcerts> upcoming() {
        List<ArtistConcerts> result = new ArrayList<>();
        for (ArtistConcerts known : store.all()) {
            List<Concert> concerts = upcoming(known.concerts());
            if (!concerts.isEmpty()) {
                result.add(new ArtistConcerts(known.artist(), concerts, known.checkedAt()));
            }
        }
        result.sort(Comparator.comparing((ArtistConcerts a) -> a.concerts().getFirst().date())
                .thenComparing(a -> ArtistNames.key(a.artist())));
        return result;
    }

    /**
     * Looks up every library and listened artist whose last lookup is older than half of
     * {@code maxAge} (so a daily scan keeps a day-old cache fresh), the most played first.
     * Stops after {@value #MAX_CONSECUTIVE_FAILURES} failures in a row.
     */
    public synchronized Result scanAll() {
        List<String> artists = artistsToScan();
        Duration stale = maxAge.dividedBy(2);
        int checked = 0;
        int withConcerts = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        boolean aborted = false;
        for (String artist : artists) {
            Optional<ArtistConcerts> cached = store.get(artist);
            if (cached.isPresent() && isFresh(cached.get(), stale)) {
                if (!upcoming(cached.get().concerts()).isEmpty()) {
                    withConcerts++;
                }
                continue;
            }
            try {
                if (!upcoming(lookUp(artist).concerts()).isEmpty()) {
                    withConcerts++;
                }
                checked++;
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                failed++;
                consecutiveFailures++;
                LOG.log(Level.WARNING, "Could not look up concerts by " + artist, e);
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    LOG.log(Level.WARNING, "Too many failures in a row; stopping this concert scan");
                    aborted = true;
                    break;
                }
            }
        }
        Result result = new Result(artists.size(), checked, withConcerts, failed, aborted);
        LOG.log(Level.INFO, "Concert scan done: {0}", result);
        return result;
    }

    private ArtistConcerts lookUp(String artist) {
        // Every country is kept, so a change of region needs no new lookups
        List<Concert> concerts = source.upcoming(artist).stream()
                .map(c -> new Concert(artist, c.title(), c.date(), c.time(), c.venue(), c.city(), c.countryCode(), c.url()))
                .sorted(Comparator.comparing(Concert::date))
                .toList();
        ArtistConcerts found = new ArtistConcerts(artist, concerts, clock.instant());
        store.save(found);
        return found;
    }

    /** Library artists by total play count, then artists played from streaming services. */
    private List<String> artistsToScan() {
        Map<String, String> names = new HashMap<>();
        Map<String, Integer> plays = new LinkedHashMap<>();
        for (Track track : library.allTracks()) {
            String name = mainArtist(track.artist());
            String key = ArtistNames.key(name);
            if (!NOT_ARTISTS.contains(key)) {
                names.putIfAbsent(key, name);
                plays.merge(key, track.playCount(), Integer::sum);
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        plays.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> ordered.add(names.get(e.getKey())));
        Set<String> keys = new LinkedHashSet<>(plays.keySet());
        for (String listened : discovered.listenedArtists()) {
            String name = mainArtist(listened);
            String key = ArtistNames.key(name);
            if (!NOT_ARTISTS.contains(key) && keys.add(key)) {
                ordered.add(name);
            }
        }
        return List.copyOf(ordered);
    }

    /** The ones in the region, from today. */
    private List<Concert> upcoming(List<Concert> concerts) {
        LocalDate today = LocalDate.now(clock);
        return concerts.stream()
                .filter(c -> countries.isEmpty() || countries.contains(c.countryCode()))
                .filter(c -> !c.date().isBefore(today))
                .toList();
    }

    private boolean isFresh(ArtistConcerts concerts, Duration age) {
        return concerts.checkedAt().isAfter(clock.instant().minus(age));
    }

    /** "Kygo feat. Selena Gomez" tours as Kygo. */
    static String mainArtist(String artist) {
        return artist == null ? "" : FEATURING.matcher(artist.strip()).replaceFirst("");
    }

}
