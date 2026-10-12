package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.model.SongTitles;
import dj.domain.model.Track;
import dj.domain.model.TrackId;
import dj.domain.port.ArtistProfileStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Ranks library tracks as candidates to follow the seeds.
 *
 * <p>Each track is scored on similar-artist match, style-tag overlap, play count, year closeness
 * and a little noise. Tracks already in the queue (or the same song from another service) are
 * excluded; artists heard in the last few tracks are penalised; and the final order is spread
 * across artists so the top of the list isn't one artist's discography.
 */
public final class CandidateScorer {

    private final ArtistProfileStore profiles;
    private final ScoringWeights weights;
    private final RandomGenerator random;

    public CandidateScorer(ArtistProfileStore profiles, ScoringWeights weights, RandomGenerator random) {
        this.profiles = Objects.requireNonNull(profiles);
        this.weights = Objects.requireNonNull(weights);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * @param library all library tracks
     * @param seeds   tracks to follow, oldest first (may be empty)
     * @param queue   everything currently in the player's queue, in order (may be empty)
     * @param limit   maximum number of candidates
     * @return candidates, best first
     */
    public List<Track> rank(Collection<Track> library, List<Track> seeds, List<Track> queue, int limit) {
        SeedProfile seed = seedProfile(seeds);
        Set<String> queuedSongs = new HashSet<>();
        Set<TrackId> queuedIds = new HashSet<>();
        for (Track track : queue) {
            queuedSongs.add(songKey(track));
            queuedIds.add(track.id());
        }
        for (Track track : seeds) {
            queuedSongs.add(songKey(track));
            queuedIds.add(track.id());
        }
        Set<String> recentArtists = recentArtists(queue.isEmpty() ? seeds : queue);
        double maxPlays = Math.log1p(library.stream().mapToInt(Track::playCount).max().orElse(0));

        Map<String, Double> artistScores = new HashMap<>();
        List<Scored> scored = new ArrayList<>();
        for (Track track : library) {
            if (queuedIds.contains(track.id()) || queuedSongs.contains(songKey(track))) {
                continue;
            }
            String artist = ArtistNames.key(track.artist());
            String group = ArtistNames.groupKey(track.artist());
            double score = artistScores.computeIfAbsent(artist, a -> artistScore(seed, a));
            score += weights.plays() * (maxPlays > 0 ? Math.log1p(track.playCount()) / maxPlays : 0);
            score += weights.year() * yearCloseness(seed.year, track.year());
            score += weights.jitter() * random.nextDouble();
            if (recentArtists.contains(group)) {
                score *= weights.recentArtistFactor();
            }
            scored.add(new Scored(track, group, score));
        }
        return spreadArtists(scored, limit);
    }

    private double artistScore(SeedProfile seed, String artist) {
        if (seed.artists.isEmpty()) {
            return 0;
        }
        Map<String, Double> tags = profiles.find(artist).map(CandidateScorer::tagVector).orElse(Map.of());
        return weights.artist() * artistSimilarity(seed, artist) + weights.tags() * cosine(seed.tags, tags);
    }

    private double artistSimilarity(SeedProfile seed, String artist) {
        double best = 0;
        for (Map.Entry<String, Double> seedArtist : seed.artists.entrySet()) {
            double similarity;
            if (seedArtist.getKey().equals(artist)) {
                similarity = weights.sameArtist();
            } else {
                similarity = Math.max(
                        match(seedArtist.getKey(), artist),
                        match(artist, seedArtist.getKey()));
            }
            best = Math.max(best, seedArtist.getValue() * similarity);
        }
        return best;
    }

    /** How similar {@code from}'s profile says {@code to} is, 0 if it doesn't mention it. */
    private double match(String from, String to) {
        return profiles.find(from).stream()
                .flatMap(p -> p.similar().stream())
                .filter(s -> ArtistNames.key(s.name()).equals(to))
                .mapToDouble(ArtistProfile.SimilarArtist::match)
                .max()
                .orElse(0);
    }

    private SeedProfile seedProfile(List<Track> seeds) {
        Map<String, Double> artists = new HashMap<>();
        Map<String, Double> tags = new HashMap<>();
        double yearSum = 0;
        double yearWeight = 0;
        double weight = 1;
        for (int i = seeds.size() - 1; i >= 0; i--, weight *= weights.seedDecay()) {
            Track seed = seeds.get(i);
            String artist = ArtistNames.key(seed.artist());
            artists.merge(artist, weight, Math::max);
            double w = weight;
            profiles.find(artist).ifPresent(p -> tagVector(p).forEach((tag, v) -> tags.merge(tag, w * v, Double::sum)));
            if (seed.year() > 0) {
                yearSum += weight * seed.year();
                yearWeight += weight;
            }
        }
        return new SeedProfile(artists, tags, yearWeight > 0 ? yearSum / yearWeight : 0);
    }

    private Set<String> recentArtists(List<Track> queue) {
        Set<String> recent = new HashSet<>();
        for (int i = Math.max(0, queue.size() - weights.recentArtistWindow()); i < queue.size(); i++) {
            recent.add(ArtistNames.groupKey(queue.get(i).artist()));
        }
        return recent;
    }

    /**
     * Greedy re-ranking: each pick of an artist makes its remaining tracks less attractive,
     * and a song is only picked in one version.
     */
    private List<Track> spreadArtists(List<Scored> scored, int limit) {
        List<Scored> remaining = new ArrayList<>(scored);
        Map<String, Integer> picksPerArtist = new HashMap<>();
        Set<String> pickedSongs = new HashSet<>();
        List<Track> ranked = new ArrayList<>(Math.min(limit, remaining.size()));
        while (ranked.size() < limit && !remaining.isEmpty()) {
            Scored best = remaining.stream()
                    .max(Comparator.comparingDouble(s -> s.score
                            * Math.pow(weights.repeatArtistFactor(), picksPerArtist.getOrDefault(s.artist, 0))))
                    .orElseThrow();
            remaining.remove(best);
            // Only the best-scoring version of each song.
            if (pickedSongs.add(songKey(best.track))) {
                picksPerArtist.merge(best.artist, 1, Integer::sum);
                ranked.add(best.track);
            }
        }
        return ranked;
    }

    private static Map<String, Double> tagVector(ArtistProfile profile) {
        Map<String, Double> vector = new HashMap<>();
        profile.tags().forEach(t -> vector.merge(t.name().toLowerCase(), t.weight(), Double::sum));
        return vector;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            dot += e.getValue() * b.getOrDefault(e.getKey(), 0.0);
        }
        double norms = norm(a) * norm(b);
        return norms == 0 ? 0 : dot / norms;
    }

    private static double norm(Map<String, Double> v) {
        return Math.sqrt(v.values().stream().mapToDouble(x -> x * x).sum());
    }

    private static double yearCloseness(double seedYear, int year) {
        if (seedYear <= 0 || year <= 0) {
            return 0;
        }
        return Math.exp(-Math.abs(seedYear - year) / 10.0);
    }

    /** Same song regardless of service or version, e.g. a Spotify original and a TIDAL remaster. */
    private static String songKey(Track track) {
        return ArtistNames.key(track.artist()) + "|" + SongTitles.key(track.title());
    }

    private record SeedProfile(Map<String, Double> artists, Map<String, Double> tags, double year) {
    }

    /** {@code artist} is the group key: side projects count as the same artist. */
    private record Scored(Track track, String artist, double score) {
    }
}
