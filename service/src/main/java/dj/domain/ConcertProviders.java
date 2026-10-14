package dj.domain;

import dj.domain.model.ConcertProvider;
import dj.domain.port.ConcertProviderChoices;
import dj.domain.port.ConcertSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every concert provider DJ knows, in priority order (a show two providers list is kept as the
 * first one has it), and which of them the listener has turned on.
 */
public class ConcertProviders {

    /**
     * @param unavailable why the provider can't be asked ("needs TICKETMASTER_API_KEY"), or empty
     */
    public record Status(ConcertProvider provider, boolean enabled, String unavailable) {

        public boolean available() {
            return unavailable.isEmpty();
        }
    }

    private record Entry(ConcertProvider provider, Optional<ConcertSource> source, String unavailable) {
    }

    private final ConcertProviderChoices choices;
    private final List<Entry> entries = new ArrayList<>();

    public ConcertProviders(ConcertProviderChoices choices) {
        this.choices = choices;
    }

    public ConcertProviders add(ConcertSource source) {
        return add(new Entry(source.provider(), Optional.of(source), ""));
    }

    /** A provider that's listed but can't be asked, and why. */
    public ConcertProviders addUnavailable(ConcertProvider provider, String reason) {
        return add(new Entry(provider, Optional.empty(), reason));
    }

    private synchronized ConcertProviders add(Entry entry) {
        if (entries.stream().anyMatch(e -> e.provider().id().equals(entry.provider().id()))) {
            throw new IllegalArgumentException("Two concert providers with id " + entry.provider().id());
        }
        entries.add(entry);
        return this;
    }

    /** The ones to ask, in priority order. */
    public synchronized List<ConcertSource> enabled() {
        Map<String, Boolean> chosen = choices.choices();
        return entries.stream()
                .filter(e -> e.source().isPresent() && isOn(e.provider(), chosen))
                .map(e -> e.source().get())
                .toList();
    }

    /** Every provider, in priority order. */
    public synchronized List<Status> all() {
        Map<String, Boolean> chosen = choices.choices();
        return entries.stream()
                .map(e -> new Status(e.provider(), e.source().isPresent() && isOn(e.provider(), chosen), e.unavailable()))
                .toList();
    }

    /**
     * Turns these providers on and every other available one off. An unavailable one that isn't
     * named keeps its earlier choice (or default), for when it becomes available: a settings page
     * can't tick a provider it shows as unavailable.
     *
     * @throws IllegalArgumentException for an id DJ doesn't know
     */
    public synchronized void choose(Set<String> enabledIds) {
        Set<String> unknown = new LinkedHashSet<>(enabledIds);
        Map<String, Boolean> chosen = new HashMap<>(choices.choices());
        for (Entry e : entries) {
            String id = e.provider().id();
            unknown.remove(id);
            if (e.source().isPresent() || enabledIds.contains(id)) {
                chosen.put(id, enabledIds.contains(id));
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown concert provider: " + String.join(", ", unknown));
        }
        choices.save(chosen);
    }

    private static boolean isOn(ConcertProvider provider, Map<String, Boolean> chosen) {
        return chosen.getOrDefault(provider.id(), provider.onByDefault());
    }
}
