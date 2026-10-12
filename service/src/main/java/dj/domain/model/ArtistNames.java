package dj.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises artist names so the same artist matches across sources:
 * "TOOL" and "Tool", "The Cure" and "Cure", "Simon & Garfunkel" and "Simon and Garfunkel".
 */
public final class ArtistNames {

    private static final Pattern FEATURING = Pattern.compile(" (feat|featuring|ft) .*$");
    private static final Pattern AND_THE = Pattern.compile(" and the .*$");
    /** Words that name a line-up rather than the artist: "Devin Townsend Project". */
    private static final Set<String> LINEUP_WORDS = Set.of(
            "project", "band", "trio", "quartet", "quintet", "group", "experience", "orchestra",
            "ensemble", "collective");

    private ArtistNames() {
    }

    /**
     * Groups an artist with their side projects and line-ups, for variety rules (not for
     * similarity): "Devin Townsend Project" and "Devin Townsend Band" → "devin townsend",
     * "Nick Cave and the Bad Seeds" → "nick cave", "Kygo feat. X" → "kygo".
     */
    public static String groupKey(String name) {
        String key = key(name);
        String group = FEATURING.matcher(key).replaceFirst("");
        group = AND_THE.matcher(group).replaceFirst("");
        int lastSpace = group.lastIndexOf(' ');
        if (lastSpace > 0 && LINEUP_WORDS.contains(group.substring(lastSpace + 1))) {
            group = group.substring(0, lastSpace);
        }
        return group.isEmpty() ? key : group;
    }

    public static String key(String name) {
        if (name == null) {
            return "";
        }
        String key = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
        if (key.startsWith("the ")) {
            key = key.substring(4);
        }
        return key;
    }
}
