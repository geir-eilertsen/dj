package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.ArtistProfile;
import dj.domain.model.Mood;
import dj.domain.model.SongTitles;
import dj.domain.model.Taste;
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
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Ranks library tracks as candidates to follow the seeds and fit the listener's mood.
 *
 * <p>Each track is scored on similar-artist match, style-tag overlap, play count, year closeness
 * and a little noise. Artist match and style overlap are measured against the seeds and, when the
 * listener has set a mood, against the mood's artists and tags, which then count most; tracks
 * outside the mood's era are penalised. Tracks already in the queue (or the same song from another service) are
 * excluded; artists heard in the last few tracks and recently skipped songs and artists are
 * penalised; and the final order is spread across artists so the top of the list isn't one
 * artist's discography. Artists that relate to neither the seeds nor the mood come after every
 * one that does, so play count and noise can't lift them above music that fits. A mood without
 * seeds favours artists close to the listener's taste among those that fit it.
 */
public final class CandidateScorer {

    /** Share of the taste match that comes from similar artists rather than shared styles. */
    static final double TASTE_BY_ARTIST = 0.6;

    private final ArtistProfileStore profiles;
    private final ScoringWeights weights;
    private final RandomGenerator random;
    private final Supplier<SkipPenalties> skips;

    public CandidateScorer(ArtistProfileStore profiles, ScoringWeights weights, RandomGenerator random) {
        this(profiles, weights, random, () -> SkipPenalties.NONE);
    }

    /** @param skips the current skip penalties, asked for once per ranking */
    public CandidateScorer(ArtistProfileStore profiles, ScoringWeights weights, RandomGenerator random,
                           Supplier<SkipPenalties> skips) {
        this.profiles = Objects.requireNonNull(profiles);
        this.weights = Objects.requireNonNull(weights);
        this.random = Objects.requireNonNull(random);
        this.skips = Objects.requireNonNull(skips);
    }

    /**
     * @param library the tracks to rank (the library, or discovered tracks)
     * @param seeds   tracks to follow, oldest first (may be empty)
     * @param queue   everything currently in the player's queue, in order (may be empty)
     * @param limit   maximum number of candidates
     * @return candidates, best first
     */
    public List<Track> rank(Collection<Track> library, List<Track> seeds, List<Track> queue, int limit) {
        return rank(library, seeds, queue, limit, Double.NEGATIVE_INFINITY);
    }

    /**
     * Like {@link #rank(Collection, List, List, int)}, but only tracks whose artist relates to
     * the seeds (similar-artist and tag score at least {@code minRelevance}) are returned. Used
     * for discovered music, which should only appear when it fits.
     */
    public List<Track> rank(Collection<Track> library, List<Track> seeds, List<Track> queue, int limit,
                            double minRelevance) {
        return rank(library, seeds, queue, limit, minRelevance, Mood.Style.NONE);
    }

    /**
     * Like {@link #rank(Collection, List, List, int, double)}, also fitting the listener's mood.
     * With a mood and no seeds, the mood alone decides relevance.
     */
    public List<Track> rank(Collection<Track> library, List<Track> seeds, List<Track> queue, int limit,
                            double minRelevance, Mood.Style mood) {
        return rank(library, seeds, queue, limit, minRelevance, mood, Taste.NONE);
    }

    /**
     * Like {@link #rank(Collection, List, List, int, double, Mood.Style)}; with a mood and no seeds,
     * artists that fit the mood count more the closer they are to the listener's taste.
     */
    public List<Track> rank(Collection<Track> library, List<Track> seeds, List<Track> queue, int limit,
                            double minRelevance, Mood.Style mood, Taste taste) {
        SeedProfile seed = seedProfile(seeds);
        MoodProfile wanted = moodProfile(mood);
        TasteProfile liked = tasteProfile(taste);
        // Closeness to the seeds' years follows the seeds, so it counts as little as they do.
        double seedShare = wanted.isEmpty() ? 1 : 1 - weights.mood();
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

        SkipPenalties penalties = skips.get();
        Map<String, Double> artistScores = new HashMap<>();
        List<Scored> scored = new ArrayList<>();
        for (Track track : library) {
            if (queuedIds.contains(track.id()) || queuedSongs.contains(songKey(track))) {
                continue;
            }
            String artist = ArtistNames.key(track.artist());
            String group = ArtistNames.groupKey(track.artist());
            double score = artistScores.computeIfAbsent(artist, a -> artistScore(seed, wanted, liked, a));
            if (score < minRelevance) {
                continue;
            }
            score += weights.plays() * (maxPlays > 0 ? Math.log1p(track.playCount()) / maxPlays : 0);
            score += seedShare * weights.year() * yearCloseness(seed.year, track.year());
            score += weights.jitter() * random.nextDouble();
            // Artists the listener asked for may be heard again.
            if (recentArtists.contains(group) && !wanted.groups.contains(group)) {
                score *= weights.recentArtistFactor();
            }
            if (!mood.fitsEra(track.year())) {
                score *= weights.outOfEraFactor();
            }
            score *= penalties.factor(track, weights);
            scored.add(new Scored(track, group, score, artistScores.get(artist) > 0));
        }
        List<Track> ranked = new ArrayList<>(spreadArtists(scored.stream().filter(Scored::related).toList(), limit));
        ranked.addAll(spreadArtists(scored.stream().filter(s -> !s.related()).toList(), limit - ranked.size()));
        return ranked;
    }

    private double artistScore(SeedProfile seed, MoodProfile mood, TasteProfile taste, String artist) {
        Map<String, Double> tags = profiles.find(artist).map(CandidateScorer::tagVector).orElse(Map.of());
        double fromSeeds = seed.artists.isEmpty() ? 0
                : weights.artist() * artistSimilarity(seed, artist) + weights.tags() * cosine(seed.tags, tags);
        if (mood.isEmpty()) {
            return fromSeeds;
        }
        double fromMood = weights.artist() * moodArtistSimilarity(mood, artist) + weights.tags() * cosine(mood.tags, tags);
        if (seed.artists.isEmpty()) {
            // Nothing to follow: among music that fits the mood, prefer what the listener likes.
            return taste.artists.isEmpty() ? fromMood
                    : fromMood * (1 + weights.taste() * tasteMatch(taste, artist, tags));
        }
        return (1 - weights.mood()) * fromSeeds + weights.mood() * fromMood;
    }

    /** 0..1: how close the artist is to the listener's favourites, by name and by style. */
    private double tasteMatch(TasteProfile taste, String artist, Map<String, Double> tags) {
        double best = 0;
        for (Map.Entry<String, Double> liked : taste.artists.entrySet()) {
            double similarity = liked.getKey().equals(artist) ? 1
                    : Math.max(match(liked.getKey(), artist), match(artist, liked.getKey()));
            best = Math.max(best, liked.getValue() * similarity);
        }
        return TASTE_BY_ARTIST * best + (1 - TASTE_BY_ARTIST) * cosine(taste.tags, tags);
    }

    private TasteProfile tasteProfile(Taste taste) {
        Map<String, Double> artists = new HashMap<>();
        Map<String, Double> tags = new HashMap<>();
        taste.artists().forEach((name, weight) -> {
            String artist = ArtistNames.key(name);
            artists.merge(artist, weight, Math::max);
            profiles.find(artist).ifPresent(p -> tagVector(p).forEach((tag, v) -> tags.merge(tag, weight * v, Double::sum)));
        });
        return new TasteProfile(artists, tags);
    }

    /** An artist the listener asked for matches fully; otherwise as similar as profiles say. */
    private double moodArtistSimilarity(MoodProfile mood, String artist) {
        double best = 0;
        for (String wanted : mood.artists) {
            best = Math.max(best, wanted.equals(artist) ? 1 : Math.max(match(wanted, artist), match(artist, wanted)));
        }
        return best;
    }

    private static MoodProfile moodProfile(Mood.Style mood) {
        Set<String> artists = new HashSet<>();
        Set<String> groups = new HashSet<>();
        mood.artists().forEach(a -> {
            artists.add(ArtistNames.key(a));
            groups.add(ArtistNames.groupKey(a));
        });
        Map<String, Double> tags = new HashMap<>();
        mood.tags().forEach(t -> tags.put(t, 1.0));
        return new MoodProfile(artists, groups, tags);
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

    /** {@code artists}: name keys and their weights; {@code tags}: the favourites' styles combined. */
    private record TasteProfile(Map<String, Double> artists, Map<String, Double> tags) {
    }

    private record SeedProfile(Map<String, Double> artists, Map<String, Double> tags, double year) {
    }

    /** {@code artists} are name keys, {@code groups} group keys. */
    private record MoodProfile(Set<String> artists, Set<String> groups, Map<String, Double> tags) {

        boolean isEmpty() {
            return artists.isEmpty() && tags.isEmpty();
        }
    }

    /**
     * {@code artist} is the group key: side projects count as the same artist. {@code related}:
     * the artist relates to the seeds or the mood at all.
     */
    private record Scored(Track track, String artist, double score, boolean related) {
    }
}
