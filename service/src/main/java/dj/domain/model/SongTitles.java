package dj.domain.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises song titles so different versions of a song match:
 * "Dreams (2001 Remaster)", "Dreams - Live" and "Dreams (feat. X)" are all "dreams",
 * while "Anabasis (Pt.1)" and "Anabasis (Pt.2)" stay distinct.
 */
public final class SongTitles {

    private static final String VERSION_WORDS = "(?i).*\\b(re-?master(ed)?|remix|mix|version|demo|live|edit|mono|stereo"
            + "|unreleased|tapes?|deluxe|bonus|acoustic|single|radio|ep|instrumental)\\b.*";
    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[]([^()\\[\\]]*)[)\\]]");
    private static final Pattern FEATURING = Pattern.compile("(?i)^(feat\\.?|featuring|ft\\.?|with)\\s.*");

    private SongTitles() {
    }

    public static String key(String title) {
        if (title == null) {
            return "";
        }
        String t = title;
        String previous;
        do {
            previous = t;
            t = BRACKETS.matcher(t).replaceAll(m -> isVersionTag(m.group(1)) ? "" : Matcher.quoteReplacement(m.group()));
            int dash = t.lastIndexOf(" - ");
            if (dash > 0 && isVersionTag(t.substring(dash + 3))) {
                t = t.substring(0, dash);
            }
        } while (!t.equals(previous));
        return ArtistNames.key(t);
    }

    private static boolean isVersionTag(String text) {
        return text.matches(VERSION_WORDS) || FEATURING.matcher(text.strip()).matches();
    }
}
