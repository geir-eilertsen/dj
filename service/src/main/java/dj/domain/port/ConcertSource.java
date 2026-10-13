package dj.domain.port;

import dj.domain.model.Concert;

import java.util.List;

/** Upcoming concerts from a ticketing service. */
public interface ConcertSource {

    /**
     * Every upcoming concert by this artist the service knows of, anywhere, in date order.
     *
     * @return an empty list if the service doesn't know the artist
     * @throws RuntimeException if the service can't be asked
     */
    List<Concert> upcoming(String artistName);
}
