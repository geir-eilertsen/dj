package dj.domain.model;

/**
 * What the photos say about the moment.
 *
 * @param mood a request in the listener's own style ("gentle 70s soft rock for a quiet morning")
 * @param why  what in the photos led there, for the listener
 */
public record PhotoReading(String mood, String why) {
}
