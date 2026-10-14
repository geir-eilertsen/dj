package dj.domain;

import dj.domain.model.Mood;
import dj.domain.port.MoodStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test fake for {@link MoodStore}. */
class InMemoryMoodStore implements MoodStore {

    private final Map<String, List<Mood>> moods = new HashMap<>();
    private final Map<String, Instant> cleared = new HashMap<>();
    /** By identity: a mood set again equals the forgotten one when the clock stands still. */
    private final Set<Mood> forgotten = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void save(String playerId, Mood mood) {
        moods.computeIfAbsent(playerId, p -> new ArrayList<>()).add(mood);
    }

    @Override
    public Optional<Mood> latest(String playerId) {
        List<Mood> list = moods.getOrDefault(playerId, List.of());
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Mood last = list.getLast();
        Instant clearedAt = cleared.get(playerId);
        return clearedAt != null && !clearedAt.isBefore(last.setAt()) ? Optional.empty() : Optional.of(last);
    }

    @Override
    public void clear(String playerId, Instant at) {
        cleared.put(playerId, at);
    }

    @Override
    public List<Mood> recent(String playerId, int limit) {
        List<Mood> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Mood mood : moods.getOrDefault(playerId, List.of()).reversed()) {
            if (!forgotten.contains(mood) && recent.size() < limit && seen.add(mood.text().toLowerCase(Locale.ROOT))) {
                recent.add(mood);
            }
        }
        return recent;
    }

    @Override
    public void forget(String playerId, String text, Instant at) {
        moods.getOrDefault(playerId, List.of()).stream()
                .filter(m -> m.text().toLowerCase(Locale.ROOT).equals(text.toLowerCase(Locale.ROOT)))
                .forEach(forgotten::add);
    }
}
