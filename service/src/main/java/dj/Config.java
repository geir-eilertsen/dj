package dj;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Service settings, read from environment variables. */
record Config(URI lmsUrl, int port, int candidatePoolSize, Duration llmTimeout, Duration libraryTtl,
              Path dataDir, Optional<String> lastFmApiKey, Duration enrichmentInterval, Duration artistMaxAge,
              Duration seedLookupTimeout) {

    static Config fromEnv(Map<String, String> env) {
        return new Config(
                URI.create(env.getOrDefault("LMS_URL", "http://localhost:9000")),
                Integer.parseInt(env.getOrDefault("DJ_PORT", "9010")),
                Integer.parseInt(env.getOrDefault("DJ_CANDIDATES", "50")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("DJ_LLM_TIMEOUT_MS", "3000"))),
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("DJ_LIBRARY_TTL_MINUTES", "15"))),
                Path.of(env.getOrDefault("DJ_DATA_DIR", "data")),
                Optional.ofNullable(env.get("LASTFM_API_KEY")).map(String::strip).filter(k -> !k.isEmpty()),
                Duration.ofHours(Long.parseLong(env.getOrDefault("DJ_ENRICH_INTERVAL_HOURS", "24"))),
                Duration.ofDays(Long.parseLong(env.getOrDefault("DJ_ARTIST_MAX_AGE_DAYS", "30"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("DJ_SEED_LOOKUP_MS", "1500"))));
    }

    /** Never log the API key. */
    @Override
    public String toString() {
        return "Config[lmsUrl=" + lmsUrl + ", port=" + port + ", candidatePoolSize=" + candidatePoolSize
                + ", llmTimeout=" + llmTimeout + ", libraryTtl=" + libraryTtl + ", dataDir=" + dataDir
                + ", lastFm=" + (lastFmApiKey.isPresent() ? "configured" : "not configured")
                + ", enrichmentInterval=" + enrichmentInterval + ", artistMaxAge=" + artistMaxAge
                + ", seedLookupTimeout=" + seedLookupTimeout + "]";
    }
}
