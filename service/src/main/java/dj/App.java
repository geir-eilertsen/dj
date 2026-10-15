package dj;

import dj.adapter.claude.ClaudeLlm;
import dj.adapter.claude.ClaudeMoodInterpreter;
import dj.adapter.claude.ClaudePhotoReader;
import dj.adapter.http.MixHttpServer;
import dj.adapter.lastfm.LastFmArtistInfo;
import dj.adapter.lms.LmsJsonRpcClient;
import dj.adapter.lms.LmsLibrary;
import dj.adapter.lms.LmsPlaybackContext;
import dj.adapter.lms.LmsPlayerActivity;
import dj.adapter.lms.LmsTidalCatalog;
import dj.adapter.sqlite.SqliteArtistProfileStore;
import dj.adapter.sqlite.SqliteConcertStore;
import dj.adapter.sqlite.SqliteDiscoveryStore;
import dj.adapter.sqlite.SqliteMoodStore;
import dj.adapter.sqlite.SqlitePlayHistory;
import dj.adapter.stub.NoLlm;
import dj.adapter.folken.FolkenConcerts;
import dj.adapter.madnesslive.MadnessLiveConcerts;
import dj.adapter.ticketmaster.TicketmasterConcerts;
import dj.adapter.stub.NoMoodInterpreter;
import dj.adapter.stub.NoPhotoReader;
import dj.adapter.stub.NoSimilarity;
import dj.domain.ArtistEnricher;
import dj.domain.CandidateScorer;
import dj.domain.ConcertFinder;
import dj.domain.ConcertProviders;
import dj.domain.Discoverer;
import dj.domain.HistoryRecorder;
import dj.domain.MixService;
import dj.domain.MixSettings;
import dj.domain.MoodService;
import dj.domain.PhotoMoods;
import dj.domain.ScoringWeights;
import dj.domain.SkipPenalties;
import dj.domain.TimeOfDaySeeds;
import dj.domain.port.LlmPort;
import dj.domain.port.MoodInterpreter;
import dj.domain.port.PhotoReader;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

        SqliteDiscoveryStore discovered = new SqliteDiscoveryStore(config.dataDir().resolve("discovery.db"));
        SqlitePlayHistory history = new SqlitePlayHistory(config.dataDir().resolve("history.db"));
        HistoryRecorder recorder = new HistoryRecorder(
                new LmsPlayerActivity(new LmsJsonRpcClient(config.lmsUrl(), Duration.ofSeconds(5))), history, history,
                Clock.systemUTC());
        Optional<LastFmArtistInfo> lastFm = config.lastFmApiKey().map(LastFmArtistInfo::new);
        Optional<ArtistEnricher> enricher = lastFm.map(source -> new ArtistEnricher(
                library, source, profiles, Clock.systemUTC(), config.artistMaxAge()));
        // TIDAL searches are on the background path only, so a generous timeout is fine.
        LmsTidalCatalog tidal = new LmsTidalCatalog(new LmsJsonRpcClient(config.lmsUrl(), Duration.ofSeconds(15)));
        Optional<Discoverer> discoverer = enricher
                .filter(e -> config.discoveryShare() > 0)
                .map(e -> new Discoverer(library, profiles, e, lastFm.get(), tidal, discovered, Clock.systemUTC(),
                        config.artistMaxAge()));

        Optional<ClaudeLlm> claude = config.anthropicApiKey().map(key -> new ClaudeLlm(
                key, config.llmModel(), config.llmLanguage(), config.llmTimeout().plusMillis(500), profiles));
        claude.ifPresent(c -> Thread.startVirtualThread(c::warmUp));
        LlmPort llm = claude.<LlmPort>map(c -> c).orElseGet(NoLlm::new);
        MoodInterpreter interpreter = config.anthropicApiKey()
                .<MoodInterpreter>map(key -> new ClaudeMoodInterpreter(key, config.llmModel(), config.llmTimeout().plusMillis(500)))
                .orElseGet(NoMoodInterpreter::new);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        MoodService moods = new MoodService(new SqliteMoodStore(config.dataDir().resolve("mood.db")), interpreter,
                profiles, library, Clock.systemUTC(), executor, config.llmTimeout());
        MixService mixService = new MixService(
                library,
                discovered,
                new NoSimilarity(),
                new CandidateScorer(profiles, ScoringWeights.DEFAULTS, new Random(), () -> {
                    Instant now = Instant.now();
                    return SkipPenalties.from(history.skipsSince(now.minus(SkipPenalties.MEMORY)), now);
                }),
                llm,
                playbackContext,
                enricher,
                discoverer,
                new TimeOfDaySeeds(history, Clock.systemDefaultZone(), new Random()),
                moods,
                history,
                Clock.systemUTC(),
                executor,
                new MixSettings(config.candidatePoolSize(), config.llmTimeout(), config.seedLookupTimeout(),
                        config.discoveryShare()));

        // In priority order: a show two of them sell is kept as the first lists it. Which ones are
        // asked is the listener's choice (plugin settings), stored with the lookups.
        SqliteConcertStore concertStore = new SqliteConcertStore(config.dataDir().resolve("concerts.db"));
        ConcertProviders concertProviders = new ConcertProviders(concertStore);
        config.ticketmasterApiKey().ifPresentOrElse(key -> concertProviders.add(new TicketmasterConcerts(key)),
                () -> concertProviders.addUnavailable(TicketmasterConcerts.PROVIDER, "needs TICKETMASTER_API_KEY"));
        concertProviders.add(new MadnessLiveConcerts(config.concertMaxAge()));
        concertProviders.add(new FolkenConcerts(config.concertMaxAge()));
        // Concert dates are in the venue's local time; "upcoming" is judged by the listener's date
        ConcertFinder concerts = new ConcertFinder(library, discovered, concertProviders, concertStore,
                Clock.systemDefaultZone(), config.concertMaxAge(), config.concertCountries());

        AtomicReference<ArtistEnricher.Result> lastEnrichment = new AtomicReference<>();
        AtomicReference<Discoverer.Result> lastDiscovery = new AtomicReference<>();
        AtomicReference<ConcertFinder.Result> lastConcertScan = new AtomicReference<>();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
        Runnable concertScan = () -> {
            try {
                lastConcertScan.set(concerts.scanAll());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Concert scan failed", e);
            }
        };
        MixHttpServer server = new MixHttpServer(mixService, history, moods, config.port(), () -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("artistProfiles", profiles.size());
            status.put("lastFm", config.lastFmApiKey().isPresent());
            status.put("llm", config.anthropicApiKey().isPresent() ? config.llmModel() : null);
            status.put("lastEnrichment", lastEnrichment.get());
            status.put("discoveredTracks", discovered.allTracks().size());
            status.put("listenedArtists", discovered.listenedArtists().size());
            status.put("lastDiscovery", lastDiscovery.get());
            status.put("concerts", concertProviders.enabled().stream().map(s -> s.provider().name()).toList());
            status.put("concertRegion", config.concertRegion());
            status.put("artistsWithConcerts", concerts.upcoming().size());
            status.put("lastConcertScan", lastConcertScan.get());
            return status;
        });
        // A provider just turned on fills in at once rather than at the next daily scan
        PhotoReader photoReader = config.anthropicApiKey()
                .<PhotoReader>map(key -> new ClaudePhotoReader(key, config.llmModel(), config.llmLanguage()))
                .orElseGet(NoPhotoReader::new);
        // The listener's own time zone, like the time-of-day seeds
        server.enablePhotoMoods(new PhotoMoods(photoReader, moods, Clock.systemDefaultZone()));
        server.enableConcerts(concerts, config.concertLookupTimeout(), () -> scheduler.execute(concertScan));
        server.start();

        // Warm the library snapshot so the first DSTM request doesn't pay for it.
        Thread.startVirtualThread(() -> {
            try {
                library.refresh();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Initial library load failed; will retry on first request", e);
            }
        });

        scheduler.scheduleWithFixedDelay(recorder::poll, 15, 10, TimeUnit.SECONDS);
        if (enricher.isPresent()) {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    lastEnrichment.set(enricher.get().enrich());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Artist enrichment failed", e);
                }
                if (discoverer.isPresent()) {
                    try {
                        lastDiscovery.set(discoverer.get().discoverAll());
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Discovery failed", e);
                    }
                }
            }, 10, config.enrichmentInterval().toSeconds(), TimeUnit.SECONDS);
        } else {
            LOG.log(Level.WARNING, "LASTFM_API_KEY not set: no artist similarity, mixes use play counts and years only");
        }

        // After the library has loaded; independent of Last.fm
        scheduler.scheduleWithFixedDelay(concertScan, 120, config.concertMaxAge().toSeconds(), TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            scheduler.shutdownNow();
            executor.shutdownNow();
        }));
    }
}
