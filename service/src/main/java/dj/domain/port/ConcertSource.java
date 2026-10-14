package dj.domain.port;

import dj.domain.model.Concert;
import dj.domain.model.ConcertProvider;

import java.util.List;

/** Upcoming concerts from one ticket seller, promoter or venue. */
public interface ConcertSource {

    ConcertProvider provider();

    /**
     * Every upcoming concert by this artist the provider knows of, anywhere, in date order.
     *
     * @return an empty list if the provider doesn't know the artist
     * @throws RuntimeException if the provider can't be asked
     */
    List<Concert> upcoming(String artistName);
}
