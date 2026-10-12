package dj;

import dj.adapter.http.MixHttpServer;
import dj.adapter.lastfm.LastFmArtistInfo;
import dj.adapter.lms.LmsJsonRpcClient;
import dj.adapter.lms.LmsLibrary;
import dj.adapter.lms.LmsPlaybackContext;
import dj.adapter.sqlite.SqliteArtistProfileStore;
import dj.adapter.stub.NoLlm;
import dj.adapter.stub.NoSimilarity;
import dj.domain.ArtistEnricher;
import dj.domain.CandidateScorer;
import dj.domain.MixService;
import dj.domain.ScoringWeights;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Wires the adapters to the domain and starts the HTTP server and background jobs. */
public final class App {

    private static final Logger LOG = System.getLogger(App.class.getName());

    private App() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnv(System.getenv());
        LOG.log(Level.INFO, "Starting with {0}", config);

        // Library loads are big but off the critical path once cached; status calls are on it.
        LmsLibrary library = new LmsLibrary(new LmsJsonRpcClient(config.lmsUrl(), Duration.ofSeconds(10)), config.libraryTtl());
        LmsPlaybackContext playbackContext =
                new LmsPlaybackContext(new LmsJsonRpcClient(config.lmsUrl(), Duration.ofSeconds(2)), Clock.systemDefaultZone());
        SqliteArtistProfileStore profiles = new SqliteArtistProfileStore(config.dataDir().resolve("dj.db"));

        Optional<ArtistEnricher> enricher = config.lastFmApiKey().map(key -> new ArtistEnricher(
                library, new LastFmArtistInfo(key), profiles, Clock.systemUTC(), config.artistMaxAge()));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        MixService mixService = new MixService(
                library,
                new NoSimilarity(),
                new CandidateScorer(profiles, ScoringWeights.DEFAULTS, new Random()),
                new NoLlm(),
                playbackContext,
                enricher,
                executor,
                config.candidatePoolSize(),
                config.llmTimeout(),
                config.seedLookupTimeout());

        AtomicReference<ArtistEnricher.Result> lastEnrichment = new AtomicReference<>();
        MixHttpServer server = new MixHttpServer(mixService, config.port(), () -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("artistProfiles", profiles.size());
            status.put("lastFm", config.lastFmApiKey().isPresent());
            status.put("lastEnrichment", lastEnrichment.get());
            return status;
        });
        server.start();

        // Warm the library snapshot so the first DSTM request doesn't pay for it.
        Thread.startVirtualThread(() -> {
            try {
                library.refresh();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Initial library load failed; will retry on first request", e);
            }
        });

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        if (enricher.isPresent()) {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    lastEnrichment.set(enricher.get().enrich());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Artist enrichment failed", e);
                }
            }, 10, config.enrichmentInterval().toSeconds(), TimeUnit.SECONDS);
        } else {
            LOG.log(Level.WARNING, "LASTFM_API_KEY not set: no artist similarity, mixes use play counts and years only");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            scheduler.shutdownNow();
            executor.shutdownNow();
        }));
    }
}
