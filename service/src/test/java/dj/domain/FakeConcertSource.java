package dj.domain;

import dj.domain.model.Concert;
import dj.domain.model.ConcertProvider;
import dj.domain.port.ConcertSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test fake for {@link ConcertSource}: answers from {@link #online}, fails for {@link #failing} artists. */
final class FakeConcertSource implements ConcertSource {

    final Map<String, List<Concert>> online = new HashMap<>();
    final Set<String> failing = new HashSet<>();
    final List<String> asked = new ArrayList<>();
    private final ConcertProvider provider;

    FakeConcertSource(String id, boolean onByDefault) {
        this.provider = new ConcertProvider(id, id, "", onByDefault);
    }

    @Override
    public ConcertProvider provider() {
        return provider;
    }

    @Override
    public List<Concert> upcoming(String artistName) {
        asked.add(artistName);
        if (failing.contains(artistName) || failing.contains("*")) {
            throw new IllegalStateException(provider.id() + " is down");
        }
        return online.getOrDefault(artistName, List.of());
    }
}
