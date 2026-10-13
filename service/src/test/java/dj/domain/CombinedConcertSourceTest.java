package dj.domain;

import dj.domain.model.Concert;
import dj.domain.port.ConcertSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombinedConcertSourceTest {

    private static Concert concert(String date, String city, String venue) {
        return new Concert("Amorphis", "", LocalDate.parse(date), null, venue, city, "ES", "");
    }

    private static final ConcertSource DOWN = artist -> {
        throw new IllegalStateException("down");
    };

    @Test
    void mergesInDateOrderKeepingTheFirstSourcesListingOfAShow() {
        ConcertSource ticketmaster = artist -> List.of(concert("2027-02-10", "Madrid", "TM venue"));
        ConcertSource promoter = artist -> List.of(concert("2027-02-09", "Barcelona", "Sala Apolo"),
                concert("2027-02-10", "MADRID", "La Riviera"));

        List<Concert> concerts = new CombinedConcertSource(List.of(ticketmaster, promoter)).upcoming("Amorphis");

        assertEquals(List.of("Sala Apolo", "TM venue"), concerts.stream().map(Concert::venue).toList());
    }

    @Test
    void failsOnlyWhenEverySourceFails() {
        ConcertSource promoter = artist -> List.of(concert("2027-02-09", "Barcelona", "Sala Apolo"));
        assertEquals(1, new CombinedConcertSource(List.of(DOWN, promoter)).upcoming("Amorphis").size());
        assertThrows(IllegalStateException.class, () -> new CombinedConcertSource(List.of(DOWN, DOWN)).upcoming("Amorphis"));
    }
}
