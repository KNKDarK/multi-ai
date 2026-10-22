package ai.os.controller.modelmanager;

import ai.os.controller.config.LlamaProperties;
import ai.os.controller.config.ModelManagerProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SingleResidentModelManager implements ModelManager {

    private static final Logger log = LoggerFactory.getLogger(SingleResidentModelManager.class);

    private static final long MB = 1024L * 1024L;

    private final LlamaProcessManager processManager;
    private final VramMonitor vramMonitor;
    private final LlamaProperties llamaProperties;
    private final ModelManagerProperties properties;
    private final Clock clock;

    private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();
    private volatile String residentModel;
    private volatile Path residentPath;
    private volatile boolean lastLoadSwapped;

    @Autowired
    public SingleResidentModelManager(LlamaProcessManager processManager, VramMonitor vramMonitor,
                                      LlamaProperties llamaProperties, ModelManagerProperties properties) {
        this(processManager, vramMonitor, llamaProperties, properties, Clock.systemUTC());
    }

    SingleResidentModelManager(LlamaProcessManager processManager, VramMonitor vramMonitor,
                               LlamaProperties llamaProperties, ModelManagerProperties properties, Clock clock) {
        this.processManager = processManager;
        this.vramMonitor = vramMonitor;
        this.llamaProperties = llamaProperties;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public synchronized void load(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Path path = resolveModelPath(modelId);
        if (modelId.equals(residentModel) && processManager.isRunning()) {
            lastLoadSwapped = false;
            lastUsed.put(modelId, clock.instant());
            return;
        }
        if (residentModel != null && !processManager.isRunning()) {
            log.warn("llama-server is not running though '{}' is resident; clearing stale residency and reloading",
                    residentModel);
            lastUsed.remove(residentModel);
            residentModel = null;
            residentPath = null;
        }
        boolean reuseProcess = residentPath != null && residentPath.equals(path) && processManager.isRunning();
        if (!reuseProcess) {
            verifyVramBudget(modelId, residentModel);
            if (residentModel != null) {
                log.info("Single-residency enforcement: evicting {} before loading {} (4GB VRAM budget)",
                        residentModel, modelId);
                unloadResident();
                lastLoadSwapped = true;
            }
        } else {
            lastLoadSwapped = false;
            log.info("Single-residency reuse: '{}' resolves to the same GGUF as resident '{}' — no swap",
                    modelId, residentModel);
        }
        if (residentPath == null || !residentPath.equals(path)) {
            try {
                processManager.start(path.toString(), llamaProperties.getPort());
            } catch (IOException e) {
                throw new IllegalStateException("failed to start llama-server for model " + modelId, e);
            }
            residentPath = path;
        }
        residentModel = modelId;
        lastUsed.put(modelId, clock.instant());
    }

    @Override
    public boolean lastLoadSwapped() {
        return lastLoadSwapped;
    }

    @Override
    public synchronized void unload(String modelId) {
        if (modelId != null && modelId.equals(residentModel)) {
            unloadResident();
        }
    }

    @Override
    public synchronized void evictIdle() {
        String current = residentModel;
        if (current == null) {
            return;
        }
        Instant last = lastUsed.get(current);
        if (last == null) {
            return;
        }
        Duration idle = Duration.between(last, clock.instant());
        Duration timeout = properties.getIdleTimeout();
        if (idle.compareTo(timeout) > 0) {
            log.info("Idle eviction: model {} idle for {} exceeds {}", current, idle, timeout);
            unloadResident();
        }
    }

    @Scheduled(fixedDelayString = "${ai-os.model-manager.idle-check-interval:10s}",
        initialDelayString = "${ai-os.model-manager.idle-check-interval:10s}")
    void scheduledIdleEviction() {
        evictIdle();
    }

    @Override
    public Optional<String> currentModel() {
        return Optional.ofNullable(residentModel);
    }

    @Override
    public Optional<Instant> lastUsed(String modelId) {
        return Optional.ofNullable(lastUsed.get(modelId));
    }

    @Override
    public Set<String> loadedModels() {
        return residentModel == null ? Set.of() : Set.of(residentModel);
    }

    private void unloadResident() {
        String evicted = residentModel;
        processManager.stop();
        lastUsed.remove(evicted);
        residentModel = null;
        residentPath = null;
        log.info("Unloaded model {}", evicted);
    }

    private void verifyVramBudget(String incoming, String current) {
        if (!vramMonitor.hasData()) {
            log.warn("VRAM telemetry unavailable ({}); skipping OOM guard for {}",
                    properties.getRocmSmiBinary(), incoming);
            return;
        }
        int incomingSizeMb = estimatedSizeMb(incoming);
        if (incomingSizeMb <= 0) {
            log.warn("Unknown VRAM size for model {}; skipping OOM guard", incoming);
            return;
        }
        long freed = current == null ? 0 : estimatedSizeMb(current);
        long availableAfterEviction = vramMonitor.availableVramMb() + freed;
        if (incomingSizeMb > availableAfterEviction) {
            throw new ModelOutOfMemoryException("model '" + incoming + "' needs ~" + incomingSizeMb
                    + " MB VRAM but only ~" + availableAfterEviction + " MB would be available after evicting "
                    + (current == null ? "nothing" : "'" + current + "'"));
        }
    }

    private int estimatedSizeMb(String modelId) {
        Long configured = properties.getModelSizes().get(modelId);
        if (configured != null && configured > 0) {
            return configured.intValue();
        }
        Path modelPath = resolveModelPath(modelId);
        if (modelPath.toFile().isFile() && modelPath.toFile().length() > 0) {
            return Math.max(1, (int) (modelPath.toFile().length() / MB));
        }
        return 0;
    }

    private Path resolveModelPath(String modelId) {
        String filename = properties.getModelCatalog().getOrDefault(modelId, modelId);
        return Path.of(llamaProperties.getModelDir(), filename);
    }
}