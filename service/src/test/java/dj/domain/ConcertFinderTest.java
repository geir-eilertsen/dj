package dj.domain;

import dj.domain.model.ArtistConcerts;
import dj.domain.model.Concert;
import dj.domain.model.ConcertLookup;
import dj.domain.model.Track;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dj.domain.Tracks.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcertFinderTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private final FakeConcertSource tm = new FakeConcertSource("tm", true);
    private final FakeConcertSource promoter = new FakeConcertSource("promoter", false);
    private final InMemoryConcertStore store = new InMemoryConcertStore();
    private final ConcertProviders providers = new ConcertProviders(store).add(tm).add(promoter);
    private final InMemoryDiscoveryStore discovered = new InMemoryDiscoveryStore();
    private List<Track> library = List.of();
    private Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Oslo"));

    private ConcertFinder finder() {
        return new ConcertFinder(() -> library, discovered, providers, store, clock, MAX_AGE);
    }

    private static Concert concert(String date, String city, String country) {
        return new Concert("TM name", "Tour", LocalDate.parse(date), null, "Venue", city, country, "https://t/" + city);
    }

    private static ConcertLookup lookup(String artist, List<Concert> concerts, Instant checkedAt) {
        return new ConcertLookup(artist, "tm", concerts, checkedAt);
    }

    @Test
    void keepsOnlyUpcomingConcertsInEuropeUnderTheLibrarysName() {
        tm.online.put("Opeth", List.of(concert("2026-12-01", "Oslo", "NO"), concert("2026-11-20", "Chicago", "US"),
                concert("2026-11-02", "London", "gb"), concert("2026-10-12", "Bergen", "NO")));

        List<Concert> concerts = finder().forArtist("Opeth");

        assertEquals(List.of("Oslo", "London"), concerts.stream().map(Concert::city).toList().reversed());
        assertEquals("Opeth", concerts.getFirst().artist());
        assertEquals("GB", concerts.getFirst().countryCode());
    }

    @Test
    void keepsTheChosenCountriesAndAppliesAChangeToCachedLookups() {
        tm.online.put("Opeth", List.of(concert("2026-11-20", "Chicago", "US"), concert("2026-12-01", "Oslo", "NO"),
                concert("2026-12-05", "Toronto", "CA")));
        finder().forArtist("Opeth");

        ConcertFinder americas = new ConcertFinder(() -> library, discovered, providers, store, clock, MAX_AGE,
                ConcertFinder.countries("us, ca"));
        assertEquals(List.of("Chicago", "Toronto"), americas.forArtist("Opeth").stream().map(Concert::city).toList());

        ConcertFinder everywhere = new ConcertFinder(() -> library, discovered, providers, store, clock, MAX_AGE,
                ConcertFinder.countries("all"));
        assertEquals(3, everywhere.forArtist("Opeth").size());
        assertEquals(1, tm.asked.size());
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
        tm.online.put("Opeth", List.of(concert("2026-12-01", "Oslo", "NO")));
        ConcertFinder finder = finder();
        finder.forArtist("Opeth");
        finder.forArtist("OPETH");
        assertEquals(1, tm.asked.size());

        clock = Clock.offset(clock, MAX_AGE.plusMinutes(1));
        finder().forArtist("Opeth");
        assertEquals(2, tm.asked.size());
    }

    @Test
    void remembersArtistsWithoutConcerts() {
        ConcertFinder finder = finder();
        assertEquals(List.of(), finder.forArtist("Gasolin'"));
        assertEquals(List.of(), finder.forArtist("Gasolin'"));
        assertEquals(1, tm.asked.size());
    }

    @Test
    void looksUpTheMainArtistOfAFeaturing() {
        finder().forArtist("Kygo feat. Selena Gomez");
        assertEquals(List.of("Kygo"), tm.asked);
    }

    @Test
    void fallsBackToAnOldLookupWhenTheSourceFails() {
        store.save(lookup("Opeth", List.of(concert("2026-12-01", "Oslo", "NO")), NOW.minus(Duration.ofDays(3))));
        tm.failing.add("Opeth");
        assertEquals(1, finder().forArtist("Opeth").size());

        tm.failing.add("Tool");
        assertThrows(IllegalStateException.class, () -> finder().forArtist("Tool"));
    }

    @Test
    void scansMostPlayedArtistsFirstAndSkipsFreshOnes() {
        library = List.of(track("1", "Tool", 0, 1), track("2", "Opeth", 0, 5), track("3", "Opeth", 0, 4),
                track("4", "Various Artists", 0, 99), track("5", "Kygo feat. Avicii", 0, 2));
        discovered.rememberListened("Wardruna", NOW);
        discovered.rememberListened("opeth", NOW);
        store.save(lookup("Tool", List.of(concert("2027-01-01", "Oslo", "NO")), NOW.minus(Duration.ofHours(2))));
        tm.online.put("Kygo", List.of(concert("2026-11-01", "Oslo", "NO")));

        ConcertFinder.Result result = finder().scanAll();

        assertEquals(List.of("Opeth", "Kygo", "Wardruna"), tm.asked);
        assertEquals(new ConcertFinder.Result(4, 3, 2, 0, List.of()), result);
    }

    @Test
    void stopsScanningAfterFailuresInARow() {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tracks.add(track(String.valueOf(i), "Band " + i, 0, 10 - i));
            tm.failing.add("Band " + i);
        }
        library = tracks;

        ConcertFinder.Result result = finder().scanAll();

        assertEquals(List.of("tm"), result.gaveUp());
        assertEquals(ConcertFinder.MAX_CONSECUTIVE_FAILURES, tm.asked.size());
    }

    @Test
    void keepsScanningWithTheProvidersThatWork() {
        providers.choose(Set.of("tm", "promoter"));
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tracks.add(track(String.valueOf(i), "Band " + i, 0, 10 - i));
        }
        library = tracks;
        tm.failing.add("*");
        promoter.online.put("Band 7", List.of(concert("2026-11-01", "Madrid", "ES")));

        ConcertFinder.Result result = finder().scanAll();

        assertEquals(new ConcertFinder.Result(8, 8, 1, 5, List.of("tm")), result);
        assertEquals(8, promoter.asked.size());
    }

    @Test
    void mergesProvidersKeepingTheFirstOnesListingOfAShow() {
        providers.choose(Set.of("tm", "promoter"));
        tm.online.put("Amorphis", List.of(new Concert("", "", LocalDate.parse("2027-02-10"), null, "TM venue", "Madrid", "ES", "")));
        promoter.online.put("Amorphis", List.of(
                new Concert("", "", LocalDate.parse("2027-02-09"), null, "Sala Apolo", "Barcelona", "ES", ""),
                new Concert("", "", LocalDate.parse("2027-02-10"), null, "La Riviera", "MADRID", "ES", "")));

        assertEquals(List.of("Sala Apolo", "TM venue"), finder().forArtist("Amorphis").stream().map(Concert::venue).toList());
        assertEquals(List.of("Sala Apolo", "TM venue"),
                finder().upcoming().getFirst().concerts().stream().map(Concert::venue).toList());
    }

    @Test
    void oneProviderFailingDoesNotLoseTheOthers() {
        providers.choose(Set.of("tm", "promoter"));
        tm.failing.add("Amorphis");
        promoter.online.put("Amorphis", List.of(concert("2027-02-09", "Barcelona", "ES")));
        assertEquals(1, finder().forArtist("Amorphis").size());

        promoter.failing.add("Opeth");
        tm.failing.add("Opeth");
        assertThrows(IllegalStateException.class, () -> finder().forArtist("Opeth"));
    }

    @Test
    void turningAProviderOffHidesItsShowsAndTurningOneOnAsksOnlyThatOne() {
        tm.online.put("Opeth", List.of(concert("2026-12-01", "Oslo", "NO")));
        promoter.online.put("Opeth", List.of(concert("2026-12-03", "Madrid", "ES")));
        assertEquals(1, finder().forArtist("Opeth").size());

        providers.choose(Set.of("tm", "promoter"));
        assertEquals(2, finder().forArtist("Opeth").size());
        assertEquals(1, tm.asked.size());
        assertEquals(1, promoter.asked.size());

        providers.choose(Set.of("promoter"));
        assertEquals(List.of("Madrid"), finder().forArtist("Opeth").stream().map(Concert::city).toList());
        assertEquals(List.of("Madrid"), finder().upcoming().getFirst().concerts().stream().map(Concert::city).toList());
        assertEquals(1, promoter.asked.size());
    }

    @Test
    void findsNothingWithEveryProviderOff() {
        tm.online.put("Opeth", List.of(concert("2026-12-01", "Oslo", "NO")));
        finder().forArtist("Opeth");
        providers.choose(Set.of());

        assertEquals(List.of(), finder().forArtist("Opeth"));
        assertEquals(List.of(), finder().upcoming());
        assertEquals(new ConcertFinder.Result(0, 0, 0, 0, List.of()), finder().scanAll());
    }

    @Test
    void listsArtistsByTheirNextConcert() {
        store.save(lookup("Tool", List.of(concert("2027-01-01", "Oslo", "NO")), NOW));
        store.save(lookup("Opeth", List.of(concert("2026-10-01", "Oslo", "NO"),
                concert("2026-11-01", "Malmö", "SE")), NOW));
        store.save(lookup("Gone", List.of(concert("2026-09-01", "Oslo", "NO")), NOW));
        store.save(lookup("Quiet", List.of(), NOW));

        List<ArtistConcerts> upcoming = finder().upcoming();

        assertEquals(List.of("Opeth", "Tool"), upcoming.stream().map(ArtistConcerts::artist).toList());
        assertEquals(1, upcoming.getFirst().concerts().size());
    }
}
