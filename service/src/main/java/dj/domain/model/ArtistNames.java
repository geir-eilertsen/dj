package dj.domain.model;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalises artist names so the same artist matches across sources:
 * "TOOL" and "Tool", "The Cure" and "Cure", "Simon & Garfunkel" and "Simon and Garfunkel".
 */
public final class ArtistNames {

    private ArtistNames() {
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
