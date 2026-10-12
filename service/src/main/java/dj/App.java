package dj;

import dj.adapter.http.MixHttpServer;
import dj.adapter.lms.LmsJsonRpcClient;
import dj.adapter.lms.LmsLibrary;
import dj.adapter.stub.BasicPlaybackContext;
import dj.adapter.stub.NoLlm;
import dj.adapter.stub.NoSimilarity;
import dj.domain.MixService;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wires the adapters to the domain and starts the HTTP server. */
public final class App {

    private static final Logger LOG = System.getLogger(App.class.getName());

    private App() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnv(System.getenv());
        LOG.log(Level.INFO, "Starting with {0}", config);

        LmsLibrary library = new LmsLibrary(new LmsJsonRpcClient(config.lmsUrl(), Duration.ofSeconds(10)), config.libraryTtl());
        ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();
        MixService mixService = new MixService(
                library,
                new NoSimilarity(),
                new NoLlm(),
                new BasicPlaybackContext(Clock.systemDefaultZone()),
                llmExecutor,
                config.candidatePoolSize(),
                config.llmTimeout());

        MixHttpServer server = new MixHttpServer(mixService, config.port());
        server.start();

        // Warm the library snapshot so the first DSTM request doesn't pay for it.
        Thread.startVirtualThread(() -> {
            try {
                library.refresh();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Initial library load failed; will retry on first request", e);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            llmExecutor.shutdownNow();
        }));
    }
}
