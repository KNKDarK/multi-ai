package ai.os.controller.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-os.model-manager")
public class ModelManagerProperties {

    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration idleCheckInterval = Duration.ofSeconds(10);
    private Duration vramPollInterval = Duration.ofSeconds(5);
    private String rocmSmiBinary = "rocm-smi";
    private Map<String, Long> modelSizes = new HashMap<>();
    private Map<String, String> modelCatalog = new HashMap<>();

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getIdleCheckInterval() {
        return idleCheckInterval;
    }

    public void setIdleCheckInterval(Duration idleCheckInterval) {
        this.idleCheckInterval = idleCheckInterval;
    }

    public Duration getVramPollInterval() {
        return vramPollInterval;
    }

    public void setVramPollInterval(Duration vramPollInterval) {
        this.vramPollInterval = vramPollInterval;
    }

    public String getRocmSmiBinary() {
        return rocmSmiBinary;
    }

    public void setRocmSmiBinary(String rocmSmiBinary) {
        this.rocmSmiBinary = rocmSmiBinary;
    }

    public Map<String, Long> getModelSizes() {
        return modelSizes;
    }

    public void setModelSizes(Map<String, Long> modelSizes) {
        this.modelSizes = modelSizes;
    }

    public Map<String, String> getModelCatalog() {
        return modelCatalog;
    }

    public void setModelCatalog(Map<String, String> modelCatalog) {
        this.modelCatalog = modelCatalog;
    }
}