package dj.domain;

import dj.domain.model.Mood;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoodKeywordsTest {

    private static final List<String> TAGS = List.of("rock", "progressive rock", "chillout", "danish", "metal");
    private static final List<String> ARTISTS = List.of("Pink Floyd", "Kim Larsen", "Yes", "TNT");

    @Test
    void findsKnownTagsAsWholeWords() {
        Mood.Style style = MoodKeywords.interpret("Some Progressive-Rock, nothing chilled", TAGS, ARTISTS);

        assertEquals(List.of("progressive rock", "rock"), style.tags());
    }

    @Test
    void findsArtistsNamedInTheRequest() {
        Mood.Style style = MoodKeywords.interpret("like pink floyd or KIM LARSEN, yes please", TAGS, ARTISTS);

        assertEquals(List.of("Pink Floyd", "Kim Larsen"), style.artists());
    }

    @Test
    void readsDecadesInSeveralSpellings() {
        assertEquals(new Mood.Style(List.of(), List.of(), 1980, 1989), MoodKeywords.interpret("80s", TAGS, ARTISTS));
        assertEquals(new Mood.Style(List.of(), List.of(), 1970, 1999),
                MoodKeywords.interpret("'70s to 1990s", TAGS, ARTISTS));
        assertEquals(new Mood.Style(List.of(), List.of(), 2000, 2009), MoodKeywords.interpret("2000s", TAGS, ARTISTS));
        assertEquals(new Mood.Style(List.of(), List.of(), 1960, 1969), MoodKeywords.interpret("60-tallet", TAGS, ARTISTS));
    }

    @Test
    void numbersThatAreNotDecadesAreIgnored() {
        assertEquals(Mood.Style.NONE, MoodKeywords.interpret("top 100 songs, 1980", TAGS, ARTISTS));
    }

    @Test
    void understandsNothingInAnUnknownRequest() {
        assertEquals(Mood.Style.NONE, MoodKeywords.interpret("something for a rainy day", TAGS, ARTISTS));
    }
}
