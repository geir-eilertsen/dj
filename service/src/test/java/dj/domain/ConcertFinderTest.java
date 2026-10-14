package dj.domain;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.Concert;
import dj.domain.model.Track;
import dj.domain.port.ConcertSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcertFinderTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private final Map<String, List<Concert>> online = new HashMap<>();
    private final Set<String> failing = new HashSet<>();
    private final List<String> asked = new ArrayList<>();
    private final ConcertSource source = artist -> {
        asked.add(artist);
        if (failing.contains(artist)) {
            throw new IllegalStateException("down");
        }
        return online.getOrDefault(artist, List.of());
    };
    private final InMemoryConcertStore store = new InMemoryConcertStore();
    private final InMemoryDiscoveryStore discovered = new InMemoryDiscoveryStore();
    private List<Track> library = List.of();
    private Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Oslo"));

    private ConcertFinder finder() {
        return new ConcertFinder(() -> library, discovered, source, store, clock, MAX_AGE);
    }

    private static Concert concert(String date, String city, String country) {
        return new Concert("TM name", "Tour", LocalDate.parse(date), null, "Venue", city, country, "https://t/" + city);
    }

    @Test
    void keepsOnlyUpcomingConcertsInEuropeUnderTheLibrarysName() {
        online.put("Opeth", List.of(concert("2026-12-01", "Oslo", "NO"), concert("2026-11-20", "Chicago", "US"),
                concert("2026-11-02", "London", "gb"), concert("2026-10-12", "Bergen", "NO")));

        List<Concert> concerts = finder().forArtist("Opeth");

        assertEquals(List.of("Oslo", "London"), concerts.stream().map(Concert::city).toList().reversed());
        assertEquals("Opeth", concerts.getFirst().artist());
        assertEquals("GB", concerts.getFirst().countryCode());
    }

    @Test
    void keepsTheChosenCountriesAndAppliesAChangeToCachedLookups() {
        online.put("Opeth", List.of(concert("2026-11-20", "Chicago", "US"), concert("2026-12-01", "Oslo", "NO"),
                concert("2026-12-05", "Toronto", "CA")));
        finder().forArtist("Opeth");

        ConcertFinder americas = new ConcertFinder(() -> library, discovered, source, store, clock, MAX_AGE,
                ConcertFinder.countries("us, ca"));
        assertEquals(List.of("Chicago", "Toronto"), americas.forArtist("Opeth").stream().map(Concert::city).toList());

        ConcertFinder everywhere = new ConcertFinder(() -> library, discovered, source, store, clock, MAX_AGE,
                ConcertFinder.countries("all"));
        assertEquals(3, everywhere.forArtist("Opeth").size());
        assertEquals(1, asked.size());
    }

    @Test
    void readsTheCountrySetting() {
        assertEquals(ConcertFinder.EUROPE, ConcertFinder.countries("europe"));
        assertTrue(ConcertFinder.countries("Europe,TR").containsAll(Set.of("TR", "NO", "ES")));
        assertEquals(Set.of("US", "CA"), ConcertFinder.countries(" us ,CA,"));
        assertEquals(Set.of(), ConcertFinder.countries("all"));
        assertEquals(Set.of(), ConcertFinder.countries(""));
        assertThrows(IllegalArgumentException.class, () -> ConcertFinder.countries("Norway"));
    }

    @Test
    void usesTheCacheUntilItIsOld() {
        online.put("Opeth", List.of(concert("2026-12-01", "Oslo", "NO")));
        ConcertFinder finder = finder();
        finder.forArtist("Opeth");
        finder.forArtist("OPETH");
        assertEquals(1, asked.size());

        clock = Clock.offset(clock, MAX_AGE.plusMinutes(1));
        finder().forArtist("Opeth");
        assertEquals(2, asked.size());
    }

    @Test
    void remembersArtistsWithoutConcerts() {
        ConcertFinder finder = finder();
        assertEquals(List.of(), finder.forArtist("Gasolin'"));
        assertEquals(List.of(), finder.forArtist("Gasolin'"));
        assertEquals(1, asked.size());
    }

    @Test
    void looksUpTheMainArtistOfAFeaturing() {
        finder().forArtist("Kygo feat. Selena Gomez");
        assertEquals(List.of("Kygo"), asked);
    }

    @Test
    void fallsBackToAnOldLookupWhenTheSourceFails() {
        store.save(new ArtistConcerts("Opeth", List.of(concert("2026-12-01", "Oslo", "NO")), NOW.minus(Duration.ofDays(3))));
        failing.add("Opeth");
        assertEquals(1, finder().forArtist("Opeth").size());

        failing.add("Tool");
        assertThrows(IllegalStateException.class, () -> finder().forArtist("Tool"));
    }

    @Test
    void scansMostPlayedArtistsFirstAndSkipsFreshOnes() {
        library = List.of(track("1", "Tool", 0, 1), track("2", "Opeth", 0, 5), track("3", "Opeth", 0, 4),
                track("4", "Various Artists", 0, 99), track("5", "Kygo feat. Avicii", 0, 2));
        discovered.rememberListened("Wardruna", NOW);
        discovered.rememberListened("opeth", NOW);
        store.save(new ArtistConcerts("Tool", List.of(concert("2027-01-01", "Oslo", "NO")), NOW.minus(Duration.ofHours(2))));
        online.put("Kygo", List.of(concert("2026-11-01", "Oslo", "NO")));

        ConcertFinder.Result result = finder().scanAll();

        assertEquals(List.of("Opeth", "Kygo", "Wardruna"), asked);
        assertEquals(new ConcertFinder.Result(4, 3, 2, 0, false), result);
    }

    @Test
    void stopsScanningAfterFailuresInARow() {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tracks.add(track(String.valueOf(i), "Band " + i, 0, 10 - i));
            failing.add("Band " + i);
        }
        library = tracks;

        ConcertFinder.Result result = finder().scanAll();

        assertTrue(result.aborted());
        assertEquals(ConcertFinder.MAX_CONSECUTIVE_FAILURES, asked.size());
    }

    @Test
    void listsArtistsByTheirNextConcert() {
        store.save(new ArtistConcerts("Tool", List.of(concert("2027-01-01", "Oslo", "NO")), NOW));
        store.save(new ArtistConcerts("Opeth", List.of(concert("2026-10-01", "Oslo", "NO"),
                concert("2026-11-01", "Malmö", "SE")), NOW));
        store.save(new ArtistConcerts("Gone", List.of(concert("2026-09-01", "Oslo", "NO")), NOW));
        store.save(new ArtistConcerts("Quiet", List.of(), NOW));

        List<ArtistConcerts> upcoming = finder().upcoming();

        assertEquals(List.of("Opeth", "Tool"), upcoming.stream().map(ArtistConcerts::artist).toList());
        assertEquals(1, upcoming.getFirst().concerts().size());
    }
}
