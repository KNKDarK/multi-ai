package ai.os.controller.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-os.llama")
public class LlamaProperties {

    private int port = 8099;
    private String binaryPath = "llama-server";
    private String modelDir = "./models";
    private Duration startTimeout = Duration.ofSeconds(60);
    private int gpuLayers;
    private int threads;
    private int batchSize;
    private int ubatchSize;
    private String device;
    private Duration requestTimeout = Duration.ofSeconds(90);
    private int maxTokens = 512;
    private boolean warmupEnabled = true;
    private String warmupModelId = "coding-small";
    private String warmupPrompt = "Reply with exactly: OK";

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
    }

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public Duration getStartTimeout() {
        return startTimeout;
    }

    public void setStartTimeout(Duration startTimeout) {
        this.startTimeout = startTimeout;
    }

    public int getGpuLayers() {
        return gpuLayers;
    }

    public void setGpuLayers(int gpuLayers) {
        this.gpuLayers = gpuLayers;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getUbatchSize() {
        return ubatchSize;
    }

    public void setUbatchSize(int ubatchSize) {
        this.ubatchSize = ubatchSize;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public boolean isWarmupEnabled() {
        return warmupEnabled;
    }

    public void setWarmupEnabled(boolean warmupEnabled) {
        this.warmupEnabled = warmupEnabled;
    }

    public String getWarmupModelId() {
        return warmupModelId;
    }

    public void setWarmupModelId(String warmupModelId) {
        this.warmupModelId = warmupModelId;
    }

    public String getWarmupPrompt() {
        return warmupPrompt;
    }

    public void setWarmupPrompt(String warmupPrompt) {
        this.warmupPrompt = warmupPrompt;
    }
}