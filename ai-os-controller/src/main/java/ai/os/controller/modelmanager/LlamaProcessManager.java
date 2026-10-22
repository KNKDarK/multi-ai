package ai.os.controller.modelmanager;

import ai.os.controller.config.LlamaProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LlamaProcessManager {

    public interface HealthProbe {
        boolean isReady(int port);
    }

    public interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(LlamaProcessManager.class);
    private static final Duration SIGTERM_WAIT = Duration.ofSeconds(10);
    private static final Duration SIGKILL_WAIT = Duration.ofSeconds(5);
    private static final long HEALTH_POLL_MS = 250;

    private final LlamaProperties properties;
    private final HealthProbe healthProbe;
    private final ProcessStarter processStarter;

    private volatile Process process;
    private volatile String runningModelPath;

    @Autowired
    public LlamaProcessManager(LlamaProperties properties) {
        this(properties, new HttpHealthProbe(), ProcessBuilder::start);
    }

    LlamaProcessManager(LlamaProperties properties, HealthProbe healthProbe, ProcessStarter processStarter) {
        this.properties = properties;
        this.healthProbe = healthProbe;
        this.processStarter = processStarter;
    }

    public synchronized void start(String modelPath, int port) throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("llama-server already running (pid " + process.pid()
                    + ", model " + runningModelPath + ")");
        }
        ProcessBuilder builder = buildProcess(modelPath, port);
        log.info("Starting llama-server: {}", String.join(" ", builder.command()));
        process = processStarter.start(builder);
        runningModelPath = modelPath;
        drainOutput(process);
        try {
            waitForReady(port);
        } catch (IOException e) {
            stop();
            throw e;
        }
        log.info("llama-server ready on port {} for model {}", port, modelPath);
    }

    ProcessBuilder buildProcess(String modelPath, int port) {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                properties.getBinaryPath(), "-m", modelPath, "--port", String.valueOf(port)));
        if (properties.getGpuLayers() > 0) {
            command.addAll(java.util.List.of("--n-gpu-layers", String.valueOf(properties.getGpuLayers())));
        }
        if (properties.getThreads() > 0) {
            command.addAll(java.util.List.of("--threads", String.valueOf(properties.getThreads())));
        }
        if (properties.getBatchSize() > 0) {
            command.addAll(java.util.List.of("--batch-size", String.valueOf(properties.getBatchSize())));
        }
        if (properties.getUbatchSize() > 0) {
            command.addAll(java.util.List.of("--ubatch-size", String.valueOf(properties.getUbatchSize())));
        }
        if (properties.getDevice() != null && !properties.getDevice().isBlank()) {
            command.addAll(java.util.List.of("--device", properties.getDevice()));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return builder;
    }

    public synchronized void stop() {
        Process target = process;
        runningModelPath = null;
        process = null;
        if (target == null || !target.isAlive()) {
            return;
        }
        log.info("Stopping llama-server pid {} with SIGTERM", target.pid());
        target.destroy();
        try {
            if (target.waitFor(SIGTERM_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.warn("SIGTERM grace period elapsed; force-killing llama-server pid {}", target.pid());
        target.destroyForcibly();
        try {
            target.waitFor(SIGKILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    @PreDestroy
    void shutdownOnContextClose() {
        stop();
    }

    public Optional<String> currentModelPath() {
        return Optional.ofNullable(runningModelPath);
    }

    private void waitForReady(int port) throws IOException {
        Instant deadline = Instant.now().plus(properties.getStartTimeout());
        while (Instant.now().isBefore(deadline)) {
            if (process != null && !process.isAlive()) {
                throw new IOException("llama-server exited before becoming ready (model " + runningModelPath + ")");
            }
            if (healthProbe.isReady(port)) {
                return;
            }
            sleep(HEALTH_POLL_MS);
        }
        throw new IOException("llama-server did not become ready on port " + port + " within "
                + properties.getStartTimeout());
    }

    private void drainOutput(Process target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(target.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[llama-server] {}", line);
                }
            } catch (IOException ignored) {
                log.warn("Lost llama-server output stream");
            }
        }, "llama-server-output");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for llama-server to become ready", e);
        }
    }

    private static final class HttpHealthProbe implements HealthProbe {

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        @Override
        public boolean isReady(int port) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }
    }
}