package dj.adapter.web;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Visible text from bits of scraped HTML. */
public final class Html {

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    /** "&oacute;" → "ó", "&Aring;" → "Å": accented letters as named entities. */
    private static final Pattern LETTER_ENTITY = Pattern.compile("&([A-Za-z])(acute|grave|circ|tilde|uml|ring|cedil);");
    private static final Map<String, String> COMBINING = Map.of("acute", "́", "grave", "̀",
            "circ", "̂", "tilde", "̃", "uml", "̈", "ring", "̊", "cedil", "̧");

    private Html() {
    }

    /** Tags dropped, entities decoded, whitespace (no-break spaces too) collapsed. */
    public static String text(String html) {
        // \s and strip() don't count the no-break spaces sites pad names with
        return unescape(html.replaceAll("<[^>]+>", "")).replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }

    public static String unescape(String s) {
        s = NUMERIC_ENTITY.matcher(s).replaceAll(m ->
                Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))));
        s = LETTER_ENTITY.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
                Normalizer.normalize(m.group(1) + COMBINING.get(m.group(2)), Normalizer.Form.NFC)));
        return s.replace("&oslash;", "ø").replace("&Oslash;", "Ø").replace("&aelig;", "æ").replace("&AElig;", "Æ")
                .replace("&szlig;", "ß").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&#39;", "'").replace("&rsquo;", "’").replace("&ndash;", "–").replace("&lt;", "<").replace("&gt;", ">");
    }
}
