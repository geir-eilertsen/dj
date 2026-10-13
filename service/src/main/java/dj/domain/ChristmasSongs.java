package dj.domain;

import dj.domain.model.Mood;
import dj.domain.model.Track;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Keeps Christmas songs for Christmas.
 *
 * <p>A song is a Christmas song when its title or album says so, in the languages of the
 * library (English, Norwegian, Danish, Swedish, German, Spanish). Artist tags are no help: an
 * artist with one Christmas album isn't a Christmas artist.
 *
 * <p>The season runs from the first Sunday of Advent to 6 January (the thirteenth day of
 * Christmas). Outside it, Christmas songs are only played when the listener asks for them,
 * in a mood or by playing one.
 */
final class ChristmasSongs {

    /** Whole words, any case. "jul" words are spelled out so that July, Julie, Julia and Jules stay out. */
    private static final Pattern CHRISTMAS = Pattern.compile("(?<![\\p{L}\\p{N}])(?:"
            + "christmas|x-?mas|santa claus|sleigh ?bells?|sleigh ride|jingle bells?|rudolph|mistletoe|"
            + "silent night|holy night|carol of the bells|little drummer boy|frosty the snowman|"
            + "winter wonderland|let it snow|o come,? all ye faithful|hark,? the herald|"
            + "jul|julen|jula|julaften|julenatt|jule(?!s(?![\\p{L}\\p{N}]))\\w*|"
            + "god jul|glade jul|stille natt|o helga natt|deilig er jorden|nissen|"
            + "weihnacht\\w*|navidad|noël"
            + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ChristmasSongs() {
    }

    static boolean isChristmasSong(Track track) {
        return mentionsChristmas(track.title()) || mentionsChristmas(track.album());
    }

    static boolean mentionsChristmas(String text) {
        return text != null && CHRISTMAS.matcher(text).find();
    }

    /** From the first Sunday of Advent (27 November to 3 December) to 6 January. */
    static boolean inSeason(LocalDate date) {
        LocalDate adventSunday = LocalDate.of(date.getYear(), Month.NOVEMBER, 27)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return !date.isBefore(adventSunday) || date.isBefore(LocalDate.of(date.getYear(), Month.JANUARY, 7));
    }

    /** Whether Christmas songs may be suggested now, for this mood and these seeds. */
    static boolean wanted(LocalDate date, Mood.Style mood, String moodText, List<Track> seeds) {
        return inSeason(date)
                || mentionsChristmas(moodText)
                || mood.tags().stream().anyMatch(ChristmasSongs::mentionsChristmas)
                || seeds.stream().anyMatch(ChristmasSongs::isChristmasSong);
    }
}
