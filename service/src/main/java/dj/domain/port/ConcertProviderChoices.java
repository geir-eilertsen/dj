package dj.domain.port;

import java.util.Map;

/** Which concert providers the listener turned on or off. Providers not in the map keep their default. */
public interface ConcertProviderChoices {

    Map<String, Boolean> choices();

    void save(Map<String, Boolean> choices);
}
