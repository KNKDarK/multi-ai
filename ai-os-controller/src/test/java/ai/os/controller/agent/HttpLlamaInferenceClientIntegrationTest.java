package ai.os.controller.agent;

import ai.os.controller.config.LlamaProperties;
import ai.os.controller.modelmanager.LlamaProcessManager;
import ai.os.controller.modelmanager.SingleResidentModelManager;
import ai.os.controller.modelmanager.VramMonitor;
import ai.os.controller.config.ModelManagerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that starts a real llama-server subprocess with a small GGUF model
 * and performs an actual inference round-trip.
 *
 * <p>This test is behind <code>@Tag("manual")</code> and an environment-variable guard
 * because it requires:
 * <ul>
 *   <li>a real llama-server binary on PATH</li>
 *   <li>a small GGUF model file on disk</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   # Bash
 *   LLAMA_MODEL=/path/to/small.gguf ./mvnw test -Dgroups=manual -pl ai-os-controller
 *
 *   # or via Maven profile
 *   ./mvnw test -P integration -Dllama.model.path=/path/to/small.gguf
 * </pre>
 */
@Tag("manual")
@EnabledIfEnvironmentVariable(named = "LLAMA_MODEL", matches = ".+")
class HttpLlamaInferenceClientIntegrationTest {

    private LlamaProcessManager processManager;
    private SingleResidentModelManager modelManager;
    private HttpLlamaInferenceClient client;

    @BeforeEach
    void setUp() {
        String modelPath = System.getenv("LLAMA_MODEL");

        LlamaProperties llamaProps = new LlamaProperties();
        llamaProps.setPort(8099); // non-default port to avoid clashing
        llamaProps.setBinaryPath("/home/knk/models/llama.cpp/build/bin/llama-server");
        llamaProps.setModelDir("/home/knk/models");
        llamaProps.setStartTimeout(Duration.ofSeconds(90));

        ModelManagerProperties mmProps = new ModelManagerProperties();
        mmProps.setIdleTimeout(Duration.ofMinutes(5));
        // resolveModelPath does modelDir + catalog.getOrDefault(modelId, modelId)
        // modelPath from env is an absolute path like /home/knk/models/qwen3-4b-q4.gguf
        // but resolveModelPath prepends modelDir. So we strip the dir prefix from the path.
        String modelFilename = modelPath.contains("/") ? modelPath.substring(modelPath.lastIndexOf('/') + 1) : modelPath;
        mmProps.setModelCatalog(Map.of("integration-test", modelFilename));

        VramMonitor vramMonitor = new VramMonitor(mmProps);

        processManager = new LlamaProcessManager(llamaProps);
        modelManager = new SingleResidentModelManager(processManager, vramMonitor, llamaProps, mmProps);
        MetricsCollector metrics = new MetricsCollector();
        metrics.begin();
        client = new HttpLlamaInferenceClient(modelManager, llamaProps, metrics,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        modelManager.unload("integration-test");
    }

    @Test
    void completeReturnsNonEmptyModelOutput() {
        String completion = client.complete(
                "Write a one-line Java method that returns the sum of two integers.",
                "integration-test");

        System.out.println("=== Inference output ===");
        System.out.println(completion);
        System.out.println("========================");

        assertThat(completion)
                .isNotBlank()
                .as("Completion should contain code-like content")
                .satisfies(c -> assertThat(c.length()).isGreaterThan(5));
    }
}
