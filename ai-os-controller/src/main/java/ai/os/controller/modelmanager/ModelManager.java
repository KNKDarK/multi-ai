package ai.os.controller.modelmanager;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface ModelManager {

    void load(String modelId);

    void unload(String modelId);

    Optional<String> currentModel();

    Optional<Instant> lastUsed(String modelId);

    Set<String> loadedModels();

    void evictIdle();

    /**
     * Whether the most recent {@link #load(String)} required evicting the previous
     * resident model and (re)starting llama-server. When true the load was expensive
     * (a real swap); when false the requested model reused the already-running
     * process. Defaults to {@code false} so mock-backed tests and alternate
     * implementations report no swap unless they track it.
     */
    default boolean lastLoadSwapped() {
        return false;
    }
}