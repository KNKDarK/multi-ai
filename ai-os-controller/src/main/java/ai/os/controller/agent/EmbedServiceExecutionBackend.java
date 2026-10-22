package ai.os.controller.agent;

import ai.os.controller.config.EmbedServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real {@link ExecutionBackend} that launches python-services/embed-service as a short-lived
 * subprocess on first use, calls its {@code POST /embed} endpoint, and shuts the subprocess
 * down again once it has been idle for {@code ai-os.embed.idle-timeout} (or when the Spring
 * context closes).
 */
@Component
public class EmbedServiceExecutionBackend implements ExecutionBackend {

    @FunctionalInterface
    public interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    public interface EmbedHttpClient {
        boolean isHealthy();

        BackendResult embed(String text);
    }

    private static final Logger log = LoggerFactory.getLogger(EmbedServiceExecutionBackend.class);
    private static final Duration SIGTERM_WAIT = Duration.ofSeconds(10);
    private static final long HEALTH_POLL_MS = 250;

    private final EmbedServiceProperties properties;
    private final Clock clock;
    private final ProcessStarter processStarter;
    private final EmbedHttpClient httpClient;

    private volatile Process process;
    private volatile Instant lastUsed;

    @Autowired
    public EmbedServiceExecutionBackend(EmbedServiceProperties properties, RestClient.Builder builder) {
        this(properties, Clock.systemUTC(), ProcessBuilder::start, new RestClientEmbedHttpClient(builder, properties));
    }

    EmbedServiceExecutionBackend(EmbedServiceProperties properties, Clock clock, ProcessStarter processStarter,
            EmbedHttpClient httpClient) {
        this.properties = properties;
        this.clock = clock;
        this.processStarter = processStarter;
        this.httpClient = httpClient;
    }

    @Override
    public synchronized BackendResult invoke(String modelId, String instruction) {
        ensureStarted();
        BackendResult result = httpClient.embed(instruction);
        lastUsed = clock.instant();
        log.info("embed-service: model={} result={}", modelId, result.output());
        return result;
    }

    public synchronized void ensureStarted() {
        if (isRunning()) {
            return;
        }
        ProcessBuilder builder = buildProcess();
        log.info("Starting embed-service: {}", String.join(" ", builder.command()));
        try {
            process = processStarter.start(builder);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch embed-service: " + properties.getRunScript(), e);
        }
        drainOutput(process);
        awaitHealthy(properties.getPort());
    }

    ProcessBuilder buildProcess() {
        ProcessBuilder builder = new ProcessBuilder(
                properties.getRunScript(), "--port", String.valueOf(properties.getPort()));
        builder.redirectErrorStream(true);
        return builder;
    }

    public synchronized void shutdown() {
        Process target = process;
        process = null;
        lastUsed = null;
        if (target == null || !target.isAlive()) {
            return;
        }
        log.info("Stopping embed-service pid {} with SIGTERM", target.pid());
        target.destroy();
        try {
            if (target.waitFor(SIGTERM_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("embed-service stopped cleanly");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.warn("SIGTERM grace period elapsed; force-killing embed-service pid {}", target.pid());
        target.destroyForcibly();
        try {
            target.waitFor(SIGTERM_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdownOnContextClose() {
        log.info("Context closing; shutting down embed-service subprocess");
        shutdown();
    }

    @Scheduled(fixedDelayString = "${ai-os.embed.idle-check-interval:10s}",
            initialDelayString = "${ai-os.embed.idle-check-interval:10s}")
    synchronized void scheduledIdleShutdown() {
        if (!isRunning() || lastUsed == null) {
            return;
        }
        if (Duration.between(lastUsed, clock.instant()).compareTo(properties.getIdleTimeout()) > 0) {
            log.info("embed-service idle for {}, shutting down", properties.getIdleTimeout());
            shutdown();
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    private void awaitHealthy(int port) {
        Instant deadline = clock.instant().plus(properties.getStartTimeout());
        while (true) {
            if (process != null && !process.isAlive()) {
                shutdown();
                throw new IllegalStateException("embed-service exited before /health was ready");
            }
            if (httpClient.isHealthy()) {
                log.info("embed-service healthy on port {}", port);
                return;
            }
            if (clock.instant().isAfter(deadline)) {
                shutdown();
                throw new IllegalStateException("embed-service did not become healthy within "
                        + properties.getStartTimeout());
            }
            sleep(HEALTH_POLL_MS);
        }
    }

    private void drainOutput(Process target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(target.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[embed-service] {}", line);
                }
            } catch (IOException ignored) {
                log.warn("Lost embed-service output stream");
            }
        }, "embed-service-output");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for embed-service to become ready", e);
        }
    }

    private static final class RestClientEmbedHttpClient implements EmbedHttpClient {

        private final RestClient restClient;

        RestClientEmbedHttpClient(RestClient.Builder builder, EmbedServiceProperties properties) {
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            this.restClient = builder.requestFactory(new JdkClientHttpRequestFactory(http))
                    .baseUrl("http://127.0.0.1:" + properties.getPort()).build();
        }

        @Override
        public boolean isHealthy() {
            try {
                return restClient.get().uri("/health").exchange((request, response) -> {
                    int code = response.getStatusCode().value();
                    return code == 200 || code == 503;
                });
            } catch (RestClientException e) {
                return false;
            }
        }

        @Override
        public BackendResult embed(String text) {
            ResponseEntity<JsonNode> response = restClient.post().uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text))
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("embed-service returned an empty /embed body");
            }
            int count = body.path("count").asInt();
            int dims = body.path("dimensions").asInt();
            return new BackendResult(true, "embed-service: embedded %d text(s), dims=%d".formatted(count, dims));
        }
    }
}