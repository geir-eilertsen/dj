package dj;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Service settings, read from environment variables. */
record Config(URI lmsUrl, int port, int candidatePoolSize, Duration llmTimeout, Duration libraryTtl) {

    static Config fromEnv(Map<String, String> env) {
        return new Config(
                URI.create(env.getOrDefault("LMS_URL", "http://localhost:9000")),
                Integer.parseInt(env.getOrDefault("DJ_PORT", "9010")),
                Integer.parseInt(env.getOrDefault("DJ_CANDIDATES", "50")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("DJ_LLM_TIMEOUT_MS", "3000"))),
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("DJ_LIBRARY_TTL_MINUTES", "15"))));
    }
}
