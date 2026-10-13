package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Concert;
import dj.domain.port.ConcertSource;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks several ticket sellers and merges their answers, in date order. A show both list (same
 * date and city) is kept once, as the first source has it. One source failing doesn't lose the
 * others' concerts; only if every source fails does the lookup fail.
 */
public class CombinedConcertSource implements ConcertSource {

    private static final Logger LOG = System.getLogger(CombinedConcertSource.class.getName());

    private final List<ConcertSource> sources;

    public CombinedConcertSource(List<ConcertSource> sources) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("need at least one concert source");
        }
        this.sources = List.copyOf(sources);
    }

    @Override
    public List<Concert> upcoming(String artistName) {
        List<Concert> all = new ArrayList<>();
        RuntimeException failure = null;
        int failed = 0;
        for (ConcertSource source : sources) {
            try {
                all.addAll(source.upcoming(artistName));
            } catch (RuntimeException e) {
                failed++;
                failure = failure == null ? e : failure;
                if (sources.size() > 1) {
                    LOG.log(Level.WARNING, "A concert source failed for " + artistName + ": " + e.getMessage());
                }
            }
        }
        if (failed == sources.size()) {
            throw failure;
        }
        Set<String> shows = new HashSet<>();
        List<Concert> merged = new ArrayList<>();
        for (Concert c : all) {
            if (shows.add(c.date() + "|" + ArtistNames.key(c.city()))) {
                merged.add(c);
            }
        }
        merged.sort(Comparator.comparing(Concert::date));
        return merged;
    }
}
