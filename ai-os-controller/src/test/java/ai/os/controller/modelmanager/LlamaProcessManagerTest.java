package ai.os.controller.modelmanager;

import ai.os.controller.config.LlamaProperties;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlamaProcessManagerTest {

    private LlamaProcessManager manager;
    private Process spawned;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
        if (spawned != null && spawned.isAlive()) {
            spawned.destroyForcibly();
        }
    }

    private LlamaProperties props(long startTimeoutSeconds) {
        LlamaProperties properties = new LlamaProperties();
        properties.setBinaryPath("/opt/rocm/bin/llama-server");
        properties.setStartTimeout(Duration.ofSeconds(startTimeoutSeconds));
        return properties;
    }

    @Test
    void buildProcessUsesConfiguredBinaryModelPathAndPort() {
        manager = new LlamaProcessManager(props(60), port -> false,
                builder -> {
                    throw new AssertionError("start must not be called");
                });

        ProcessBuilder builder = manager.buildProcess("/models/code.gguf", 8123);

        assertThat(builder.command()).containsExactly(
                "/opt/rocm/bin/llama-server", "-m", "/models/code.gguf", "--port", "8123");
    }

    @Test
    void buildProcessIncludesConfiguredRuntimeFlags() {
        LlamaProperties properties = props(60);
        properties.setGpuLayers(16);
        properties.setThreads(6);
        properties.setBatchSize(128);
        properties.setUbatchSize(64);
        properties.setDevice("Vulkan1");
        manager = new LlamaProcessManager(properties, port -> false,
                builder -> {
                    throw new AssertionError("start must not be called");
                });

        ProcessBuilder builder = manager.buildProcess("/models/code.gguf", 8123);

        assertThat(builder.command()).containsExactly(
                "/opt/rocm/bin/llama-server", "-m", "/models/code.gguf", "--port", "8123",
                "--n-gpu-layers", "16", "--threads", "6", "--batch-size", "128",
                "--ubatch-size", "64", "--device", "Vulkan1");
    }

    @Test
    void startWaitsForHealthThenMarksRunning() throws Exception {
        spawned = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
        manager = new LlamaProcessManager(props(60), port -> port == 8123, builder -> spawned);

        manager.start("/models/code.gguf", 8123);

        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.currentModelPath()).contains("/models/code.gguf");
    }

    @Test
    void startThrowsWhenProcessExitsBeforeBecomingReady() throws Exception {
        spawned = new ProcessBuilder("sleep", "1").redirectErrorStream(true).start();
        manager = new LlamaProcessManager(props(60), port -> false, builder -> spawned);

        assertThatThrownBy(() -> manager.start("/models/code.gguf", 8123))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited before becoming ready");
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void startThrowsWhenHealthNeverBecomesReadyAndCleansUpProcess() throws Exception {
        spawned = new ProcessBuilder("sleep", "120").redirectErrorStream(true).start();
        manager = new LlamaProcessManager(props(1), port -> false, builder -> spawned);

        assertThatThrownBy(() -> manager.start("/models/code.gguf", 8123))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not become ready");
        assertThat(spawned.isAlive()).isFalse();
    }

    @Test
    void stopTerminatesRunningProcess() throws Exception {
        spawned = new ProcessBuilder("sleep", "60").redirectErrorStream(true).start();
        LlamaProcessManager processManager = new LlamaProcessManager(props(60), port -> true, builder -> spawned);
        processManager.start("/models/code.gguf", 8123);

        processManager.stop();

        assertThat(spawned.isAlive()).isFalse();
    }

    @Test
    void stopIsSafeWhenNothingRunning() {
        manager = new LlamaProcessManager(props(60), port -> false,
                builder -> {
                    throw new AssertionError("start must not be called");
                });

        manager.stop();

        assertThat(manager.isRunning()).isFalse();
        assertThat(manager.currentModelPath()).isEmpty();
    }

    @Test
    void startRejectsWhenAlreadyRunning() throws Exception {
        spawned = new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
        manager = new LlamaProcessManager(props(60), port -> true, builder -> spawned);
        manager.start("/models/code.gguf", 8123);

        assertThatThrownBy(() -> manager.start("/models/other.gguf", 8124))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");

        assertThat(manager.currentModelPath()).contains("/models/code.gguf");
    }
}