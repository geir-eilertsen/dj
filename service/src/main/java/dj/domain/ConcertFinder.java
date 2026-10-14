package dj.domain;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.model.ConcertLookup;
import dj.domain.model.Track;
import dj.domain.port.ConcertSource;
import dj.domain.port.ConcertStore;
import dj.domain.port.DiscoveryStore;
import dj.domain.port.LibraryPort;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>Each enabled provider is asked separately and its lookups are cached in the
 * {@link ConcertStore} for {@code maxAge}, including empty ones, so an artist is asked about at
 * most about once per {@code maxAge} per provider. Their answers are merged when read: turning a
 * provider off hides its shows at once, and turning one on asks only that one. A show two
 * providers list (same date and city) is kept as the first one in priority order has it.
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

    /**
     * @param checked artists at least one provider was asked about
     * @param failed  lookups that failed
     * @param gaveUp  providers that failed {@value #MAX_CONSECUTIVE_FAILURES} times in a row and
     *                weren't asked again in this scan
     */
    public record Result(int artists, int checked, int withConcerts, int failed, List<String> gaveUp) {
    }

    private final LibraryPort library;
    private final DiscoveryStore discovered;
    private final ConcertProviders providers;
    private final ConcertStore store;
    private final Clock clock;
    private final Duration maxAge;
    private final Set<String> countries;

    /** Concerts in Europe ({@link #EUROPE}). */
    public ConcertFinder(LibraryPort library, DiscoveryStore discovered, ConcertProviders providers, ConcertStore store,
                         Clock clock, Duration maxAge) {
        this(library, discovered, providers, store, clock, maxAge, EUROPE);
    }

    /**
     * @param clock     also gives the time zone that decides which concerts are still upcoming
     * @param countries ISO 3166-1 alpha-2 codes of the countries to keep; empty keeps every country
     */
    public ConcertFinder(LibraryPort library, DiscoveryStore discovered, ConcertProviders providers, ConcertStore store,
                         Clock clock, Duration maxAge, Set<String> countries) {
        this.library = library;
        this.discovered = discovered;
        this.providers = providers;
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

    public ConcertProviders providers() {
        return providers;
    }

    /**
     * Upcoming concerts in the region by this artist, from the cache where it's fresh. A provider
     * that fails gives its last answer, if any.
     *
     * @throws RuntimeException if every provider that was asked failed and nothing is known
     */
    public List<Concert> forArtist(String artist) {
        String name = mainArtist(artist);
        if (NOT_ARTISTS.contains(ArtistNames.key(name))) {
            return List.of();
        }
        List<List<Concert>> found = new ArrayList<>();
        RuntimeException failure = null;
        for (ConcertSource source : providers.enabled()) {
            Optional<ConcertLookup> cached = store.get(name, source.provider().id());
            if (cached.isPresent() && isFresh(cached.get(), maxAge)) {
                found.add(cached.get().concerts());
                continue;
            }
            try {
                found.add(lookUp(source, name).concerts());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, source.provider().name() + " concert lookup for " + name + " failed"
                        + (cached.isPresent() ? "; using the last one" : ""), e);
                cached.ifPresent(c -> found.add(c.concerts()));
                failure = failure == null ? e : failure;
            }
        }
        if (found.isEmpty() && failure != null) {
            throw failure;
        }
        return upcoming(merge(found));
    }

    /** Every known artist with upcoming concerts, the soonest first. */
    public List<ArtistConcerts> upcoming() {
        List<String> order = providers.enabled().stream().map(s -> s.provider().id()).toList();
        Map<String, List<ConcertLookup>> byArtist = new LinkedHashMap<>();
        for (ConcertLookup lookup : store.all()) {
            if (order.contains(lookup.provider())) {
                byArtist.computeIfAbsent(ArtistNames.key(lookup.artist()), k -> new ArrayList<>()).add(lookup);
            }
        }
        List<ArtistConcerts> result = new ArrayList<>();
        for (List<ConcertLookup> lookups : byArtist.values()) {
            lookups.sort(Comparator.comparingInt(l -> order.indexOf(l.provider())));
            List<Concert> concerts = upcoming(merge(lookups.stream().map(ConcertLookup::concerts).toList()));
            if (!concerts.isEmpty()) {
                Instant checkedAt = lookups.stream().map(ConcertLookup::checkedAt).max(Comparator.naturalOrder()).orElseThrow();
                result.add(new ArtistConcerts(lookups.getFirst().artist(), concerts, checkedAt));
            }
        }
        result.sort(Comparator.comparing((ArtistConcerts a) -> a.concerts().getFirst().date())
                .thenComparing(a -> ArtistNames.key(a.artist())));
        return result;
    }

    /**
     * Asks every enabled provider about every library and listened artist whose last lookup there
     * is older than half of {@code maxAge} (so a daily scan keeps a day-old cache fresh), the most
     * played artists first. A provider that fails {@value #MAX_CONSECUTIVE_FAILURES} times in a
     * row isn't asked again in this scan; the others carry on.
     */
    public synchronized Result scanAll() {
        List<String> artists = artistsToScan();
        List<ConcertSource> sources = providers.enabled();
        Duration stale = maxAge.dividedBy(2);
        int checked = 0;
        int withConcerts = 0;
        int failed = 0;
        Map<String, Integer> failuresInARow = new HashMap<>();
        Set<String> gaveUp = new LinkedHashSet<>();
        for (String artist : artists) {
            if (!sources.isEmpty() && gaveUp.size() == sources.size()) {
                LOG.log(Level.WARNING, "Every concert provider is failing; stopping this concert scan");
                break;
            }
            List<List<Concert>> found = new ArrayList<>();
            boolean asked = false;
            for (ConcertSource source : sources) {
                String id = source.provider().id();
                Optional<ConcertLookup> cached = store.get(artist, id);
                if (gaveUp.contains(id) || cached.isPresent() && isFresh(cached.get(), stale)) {
                    cached.ifPresent(c -> found.add(c.concerts()));
                    continue;
                }
                asked = true;
                try {
                    found.add(lookUp(source, artist).concerts());
                    failuresInARow.put(id, 0);
                } catch (RuntimeException e) {
                    failed++;
                    cached.ifPresent(c -> found.add(c.concerts()));
                    LOG.log(Level.WARNING, "Could not look up concerts by " + artist + " at " + source.provider().name(), e);
                    if (failuresInARow.merge(id, 1, Integer::sum) >= MAX_CONSECUTIVE_FAILURES) {
                        LOG.log(Level.WARNING, "Too many failures in a row; not asking {0} again in this scan",
                                source.provider().name());
                        gaveUp.add(id);
                    }
                }
            }
            if (asked) {
                checked++;
            }
            if (!upcoming(merge(found)).isEmpty()) {
                withConcerts++;
            }
        }
        Result result = new Result(artists.size(), checked, withConcerts, failed, List.copyOf(gaveUp));
        LOG.log(Level.INFO, "Concert scan done: {0}", result);
        return result;
    }

    private ConcertLookup lookUp(ConcertSource source, String artist) {
        // Every country is kept, so a change of region needs no new lookups
        List<Concert> concerts = source.upcoming(artist).stream()
                .map(c -> new Concert(artist, c.title(), c.date(), c.time(), c.venue(), c.city(), c.countryCode(), c.url()))
                .sorted(Comparator.comparing(Concert::date))
                .toList();
        ConcertLookup found = new ConcertLookup(artist, source.provider().id(), concerts, clock.instant());
        store.save(found);
        return found;
    }

    /** One list in date order, each show (date and city) once, as the first list has it. */
    static List<Concert> merge(List<List<Concert>> lists) {
        Set<String> shows = new HashSet<>();
        List<Concert> merged = new ArrayList<>();
        for (List<Concert> concerts : lists) {
            for (Concert c : concerts) {
                if (shows.add(c.date() + "|" + ArtistNames.key(c.city()))) {
                    merged.add(c);
                }
            }
        }
        merged.sort(Comparator.comparing(Concert::date));
        return merged;
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

    private boolean isFresh(ConcertLookup lookup, Duration age) {
        return lookup.checkedAt().isAfter(clock.instant().minus(age));
    }

    /** "Kygo feat. Selena Gomez" tours as Kygo. */
    static String mainArtist(String artist) {
        return artist == null ? "" : FEATURING.matcher(artist.strip()).replaceFirst("");
    }

}
