package ai.os.controller.modelmanager;

import ai.os.controller.config.LlamaProperties;
import ai.os.controller.config.ModelManagerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleResidentModelManagerTest {

    private LlamaProcessManager processManager;
    private VramMonitor vramMonitor;
    private LlamaProperties llamaProperties;
    private ModelManagerProperties managerProperties;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        processManager = Mockito.mock(LlamaProcessManager.class);
        vramMonitor = Mockito.mock(VramMonitor.class);
        llamaProperties = new LlamaProperties();
        llamaProperties.setPort(8090);
        llamaProperties.setModelDir("./models");
        managerProperties = new ModelManagerProperties();
        clock = new MutableClock(Instant.parse("2026-09-13T12:00:00Z"));
        // A freshly-started llama-server is alive; crash tests re-stub this to false.
        when(processManager.isRunning()).thenReturn(true);
    }

    private SingleResidentModelManager manager() {
        return new SingleResidentModelManager(processManager, vramMonitor, llamaProperties, managerProperties, clock);
    }

    @Test
    void loadStartsLlamaServerAndTracksLastUsed() throws Exception {
        SingleResidentModelManager manager = manager();

        manager.load("code-model.gguf");

        verify(processManager).start("./models/code-model.gguf", 8090);
        assertThat(manager.currentModel()).contains("code-model.gguf");
        assertThat(manager.lastUsed("code-model.gguf")).contains(clock.instant());
    }

    @Test
    void loadEvictsCurrentModelBeforeStartingNewOne() throws Exception {
        when(vramMonitor.hasData()).thenReturn(true);
        when(vramMonitor.availableVramMb()).thenReturn(4096L);
        managerProperties.setModelSizes(Map.of("model-a.gguf", 1500L, "model-b.gguf", 2000L));
        SingleResidentModelManager manager = manager();

        manager.load("model-a.gguf");
        manager.load("model-b.gguf");

        verify(processManager).stop();
        verify(processManager, times(2)).start(anyString(), anyInt());
        assertThat(manager.loadedModels()).containsExactly("model-b.gguf");
        assertThat(manager.lastLoadSwapped()).isTrue();
    }

    @Test
    void reloadingSameModelDoesNotRestartLlamaServer() throws Exception {
        SingleResidentModelManager manager = manager();

        manager.load("model-a.gguf");
        manager.load("model-a.gguf");

        verify(processManager, times(1)).start(anyString(), anyInt());
        assertThat(manager.currentModel()).contains("model-a.gguf");
        assertThat(manager.lastLoadSwapped()).isFalse();
    }

    @Test
    void loadRestartsServerWhenResidentModelIsDead() throws Exception {
        SingleResidentModelManager manager = manager();

        manager.load("model-a.gguf");
        verify(processManager).start("./models/model-a.gguf", 8090);

        when(processManager.isRunning()).thenReturn(false);

        manager.load("model-a.gguf");

        verify(processManager, times(2)).start(anyString(), anyInt());
        assertThat(manager.currentModel()).contains("model-a.gguf");
        assertThat(manager.lastLoadSwapped()).isFalse();
    }

    @Test
    void loadReusesProcessAfterCrashWhenDifferentKeyMapsToSameFile() throws Exception {
        managerProperties.setModelCatalog(Map.of(
                "key-a.gguf", "shared.gguf",
                "key-b.gguf", "shared.gguf"));
        SingleResidentModelManager manager = manager();

        manager.load("key-a.gguf");
        when(processManager.isRunning()).thenReturn(false);

        manager.load("key-b.gguf");

        verify(processManager, times(2)).start("./models/shared.gguf", 8090);
        assertThat(manager.currentModel()).contains("key-b.gguf");
        assertThat(manager.lastLoadSwapped()).isFalse();
    }

    @Test
    void switchingKeyThatResolvesToSameFileReusesResidentProcess() throws Exception {
        managerProperties.setModelCatalog(Map.of(
                "large-big.gguf", "shared.gguf",
                "large-small.gguf", "shared.gguf"));
        SingleResidentModelManager manager = manager();

        manager.load("large-big.gguf");
        manager.load("large-small.gguf");

        verify(processManager, never()).stop();
        verify(processManager, times(1)).start(anyString(), anyInt());
        assertThat(manager.currentModel()).contains("large-small.gguf");
        assertThat(manager.loadedModels()).containsExactly("large-small.gguf");
        assertThat(manager.lastLoadSwapped()).isFalse();
    }

    @Test
    void unloadStopsLlamaServerAndClearsState() throws Exception {
        SingleResidentModelManager manager = manager();
        manager.load("model-a.gguf");

        manager.unload("model-a.gguf");

        verify(processManager).stop();
        assertThat(manager.currentModel()).isEmpty();
        assertThat(manager.lastUsed("model-a.gguf")).isEmpty();
    }

    @Test
    void idleModelIsEvictedAfterTimeout() throws Exception {
        managerProperties.setIdleTimeout(Duration.ofMinutes(5));
        SingleResidentModelManager manager = manager();
        manager.load("model-a.gguf");

        clock.advance(Duration.ofMinutes(6));
        manager.evictIdle();

        verify(processManager).stop();
        assertThat(manager.currentModel()).isEmpty();
        assertThat(manager.lastUsed("model-a.gguf")).isEmpty();
    }

    @Test
    void recentlyUsedModelIsNotEvicted() throws Exception {
        managerProperties.setIdleTimeout(Duration.ofMinutes(5));
        SingleResidentModelManager manager = manager();
        manager.load("model-a.gguf");

        clock.advance(Duration.ofMinutes(4));
        manager.evictIdle();

        verify(processManager, never()).stop();
        assertThat(manager.currentModel()).contains("model-a.gguf");
    }

    @Test
    void evictIdleIsNoOpWhenNothingLoaded() {
        SingleResidentModelManager manager = manager();

        manager.evictIdle();

        verify(processManager, never()).stop();
        assertThat(manager.currentModel()).isEmpty();
    }

    @Test
    void oomGuardRejectsModelThatDoesNotFit() throws Exception {
        when(vramMonitor.hasData()).thenReturn(true);
        when(vramMonitor.availableVramMb()).thenReturn(3000L);
        managerProperties.setModelSizes(Map.of("huge.gguf", 6000L));
        SingleResidentModelManager manager = manager();

        assertThatThrownBy(() -> manager.load("huge.gguf"))
                .isInstanceOf(ModelOutOfMemoryException.class)
                .hasMessageContaining("huge.gguf")
                .hasMessageContaining("6000")
                .hasMessageContaining("3000");

        verify(processManager, never()).start(anyString(), anyInt());
        assertThat(manager.currentModel()).isEmpty();
    }

    @Test
    void oomGuardAccountsForMemoryFreedByEvictingCurrentModel() throws Exception {
        when(vramMonitor.hasData()).thenReturn(true);
        when(vramMonitor.availableVramMb()).thenReturn(4000L, 1000L);
        managerProperties.setModelSizes(Map.of("small.gguf", 3000L, "medium.gguf", 3800L));
        SingleResidentModelManager manager = manager();

        manager.load("small.gguf");
        manager.load("medium.gguf");

        verify(processManager).stop();
        verify(processManager, times(2)).start(anyString(), anyInt());
        assertThat(manager.loadedModels()).containsExactly("medium.gguf");
    }

    @Test
    void oomGuardRejectsEvenWhenCurrentModelWouldFreeMemory() throws Exception {
        when(vramMonitor.hasData()).thenReturn(true);
        when(vramMonitor.availableVramMb()).thenReturn(4000L, 700L);
        managerProperties.setModelSizes(Map.of("small.gguf", 3000L, "huge.gguf", 3800L));
        SingleResidentModelManager manager = manager();

        manager.load("small.gguf");
        assertThatThrownBy(() -> manager.load("huge.gguf"))
                .isInstanceOf(ModelOutOfMemoryException.class)
                .hasMessageContaining("~3700")
                .hasMessageContaining("huge.gguf");

        verify(processManager, never()).stop();
        assertThat(manager.currentModel()).contains("small.gguf");
    }

    @Test
    void oomGuardIsSkippedWhenVramTelemetryUnavailable() throws Exception {
        when(vramMonitor.hasData()).thenReturn(false);
        managerProperties.setModelSizes(Map.of("huge.gguf", 6000L));
        SingleResidentModelManager manager = manager();

        manager.load("huge.gguf");

        verify(processManager).start("./models/huge.gguf", 8090);
        assertThat(manager.currentModel()).contains("huge.gguf");
    }

    @Test
    void blankModelIdIsRejected() {
        SingleResidentModelManager manager = manager();

        assertThatThrownBy(() -> manager.load("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone = ZoneOffset.UTC;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}