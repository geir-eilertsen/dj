package dj.domain.model;

import java.util.Objects;

/**
 * A ticket seller, promoter or venue DJ can ask for concerts, as shown in the plugin settings.
 *
 * @param id          stable, used in settings and the concert store ("ticketmaster")
 * @param coverage    what it covers, for the listener ("Worldwide", "Folken, Stavanger (Norway)")
 * @param onByDefault whether it's asked until the listener says otherwise; regional ones are off
 *                    so that nobody crawls a site that's of no use to them
 */
public record ConcertProvider(String id, String name, String coverage, boolean onByDefault) {

    public ConcertProvider {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Provider ids are lower-case letters, digits and dashes: " + id);
        }
        name = Objects.requireNonNullElse(name, id);
        coverage = Objects.requireNonNullElse(coverage, "");
    }
}
