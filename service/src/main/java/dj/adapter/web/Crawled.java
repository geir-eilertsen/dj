package dj.adapter.web;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A site's catalogue, crawled again when it's older than {@code maxAge}. A failed crawl keeps the
 * last catalogue and isn't retried for {@link #RETRY_AFTER}, so a scan of many artists doesn't
 * hammer a site that's down.
 */
public class Crawled<T> {

    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    private static final Logger LOG = System.getLogger(Crawled.class.getName());

    private final String site;
    private final Supplier<T> crawl;
    private final Duration maxAge;
    private final Clock clock;
    private T catalogue;
    private Instant crawledAt;
    private Instant retryAt = Instant.MIN;

    public Crawled(String site, Supplier<T> crawl, Duration maxAge, Clock clock) {
        this.site = site;
        this.crawl = crawl;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** @throws RuntimeException if the crawl fails and there's no earlier catalogue */
    public synchronized T get() {
        Instant now = clock.instant();
        if (crawledAt != null && crawledAt.isAfter(now.minus(maxAge))) {
            return catalogue;
        }
        if (now.isBefore(retryAt)) {
            if (crawledAt != null) {
                return catalogue;
            }
            throw new IllegalStateException(site + " is unavailable; retrying after " + retryAt);
        }
        try {
            catalogue = crawl.get();
            crawledAt = clock.instant();
            return catalogue;
        } catch (RuntimeException e) {
            retryAt = now.plus(RETRY_AFTER);
            if (crawledAt != null) {
                LOG.log(Level.WARNING, site + " crawl failed; keeping the last one: " + e.getMessage());
                return catalogue;
            }
            throw e;
        }
    }
}
