package dj.domain;

import dj.domain.model.ArtistNames;
import dj.domain.model.Mood;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands a request without any online help, by spotting known style tags, artist names
 * and decades in it ("90s", "1970s", "80-tallet"). Used when no LLM is configured or it fails;
 * "something like Pink Floyd for a rainy day" finds Pink Floyd but not the rain.
 */
final class MoodKeywords {

    static final int MAX_TAGS = 8;
    static final int MAX_ARTISTS = 5;
    /** Shorter artist names ("Yes", "Air") are ordinary words too often. */
    static final int MIN_ARTIST_KEY_LENGTH = 4;

    private static final Pattern DECADE =
            Pattern.compile("(?<![\\d])(19|20)?['’]?(\\d)0(?:['’]?s|-tall\\w*)(?![\\w])", Pattern.CASE_INSENSITIVE);

    private MoodKeywords() {
    }

    static Mood.Style interpret(String request, Collection<String> knownTags, Collection<String> artists) {
        String text = " " + words(request) + " ";

        List<String> tags = knownTags.stream()
                .filter(tag -> !words(tag).isEmpty() && text.contains(" " + words(tag) + " "))
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_TAGS)
                .toList();

        String key = " " + ArtistNames.key(request) + " ";
        List<String> named = new ArrayList<>();
        for (String artist : artists) {
            String artistKey = ArtistNames.key(artist);
            if (artistKey.length() >= MIN_ARTIST_KEY_LENGTH && key.contains(" " + artistKey + " ")
                    && named.stream().noneMatch(a -> ArtistNames.key(a).equals(artistKey))) {
                named.add(artist);
            }
        }

        int from = 0;
        int to = 0;
        Matcher decade = DECADE.matcher(request);
        while (decade.find()) {
            int digit = Integer.parseInt(decade.group(2));
            int century = decade.group(1) != null ? Integer.parseInt(decade.group(1)) * 100 : digit >= 3 ? 1900 : 2000;
            int start = century + digit * 10;
            from = from == 0 ? start : Math.min(from, start);
            to = Math.max(to, start + 9);
        }
        return new Mood.Style(tags, named.stream().limit(MAX_ARTISTS).toList(), from, to);
    }

    /** Lower case, letters and digits only, single spaces. */
    private static String words(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
