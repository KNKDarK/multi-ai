package ai.os.controller.agent;

import ai.os.controller.config.EmbedServiceProperties;
import ai.os.controller.agent.EmbedServiceExecutionBackend.EmbedHttpClient;
import ai.os.controller.agent.EmbedServiceExecutionBackend.ProcessStarter;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbedServiceExecutionBackendTest {

    private final EmbedServiceProperties props = new EmbedServiceProperties();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void buildProcessUsesConfiguredRunScriptAndPort() {
        props.setRunScript("stub/run.sh");
        props.setPort(8199);
        EmbedServiceExecutionBackend backend = backend(pb -> {
            throw new AssertionError("not started");
        }, noHttp());

        assertThat(backend.buildProcess().command()).containsExactly("stub/run.sh", "--port", "8199");
    }

    @Test
    void invokeStartsSubprocessOnceThenReusesIt() {
        Process p = aliveProcess();
        AtomicInteger starts = new AtomicInteger();
        EmbedHttpClient http = http(healthy(), "ok");

        EmbedServiceExecutionBackend backend = backend(pb -> {
            starts.incrementAndGet();
            return p;
        }, http);

        backend.invoke("embed", "first");
        backend.invoke("embed", "second");

        assertThat(starts).hasValue(1);
        verify(http, times(2)).embed(anyString());
        assertThat(backend.isRunning()).isTrue();
    }

    @Test
    void invokeWaitsForHealthBeforeCallingEmbed() {
        EmbedHttpClient http = mock(EmbedHttpClient.class);
        when(http.isHealthy()).thenReturn(false, true);
        when(http.embed(anyString())).thenReturn(new BackendResult(true, "ok"));
        EmbedServiceExecutionBackend backend = backend(pb -> aliveProcess(), http);

        backend.invoke("embed", "hi");

        verify(http).embed("hi");
        verify(http, times(2)).isHealthy();
    }

    @Test
    void invokeThrowsWhenSubprocessExitsBeforeReady() {
        Process p = mock(Process.class);
        when(p.isAlive()).thenReturn(false);
        when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        EmbedServiceExecutionBackend backend = backend(pb -> p, noHttp());

        assertThatThrownBy(() -> backend.invoke("embed", "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exited before");
    }

    @Test
    void shutdownDestroysRunningSubprocess() {
        Process p = aliveProcess();
        EmbedServiceExecutionBackend backend = backend(pb -> p, http(healthy(), "ok"));
        backend.invoke("embed", "hi");

        backend.shutdown();

        verify(p).destroy();
        assertThat(backend.isRunning()).isFalse();
    }

    @Test
    void shutdownIsSafeWhenNothingRunning() {
        backend(pb -> {
            throw new AssertionError("not started");
        }, noHttp()).shutdown();
    }

    @Test
    void idleShutdownKeepsRecentProcessAliveThenKillsItAfterTimeout() {
        props.setIdleTimeout(Duration.ofSeconds(5));
        Process p = aliveProcess();
        EmbedServiceExecutionBackend backend = backend(pb -> p, http(healthy(), "ok"));
        backend.invoke("embed", "hi");

        backend.scheduledIdleShutdown();
        verify(p, never()).destroy();

        clock.advance(Duration.ofSeconds(6));
        backend.scheduledIdleShutdown();

        verify(p).destroy();
        assertThat(backend.isRunning()).isFalse();
    }

    @Test
    void idleShutdownDoesNotKillProcessThatHasNotCompletedARequest() {
        Process p = aliveProcess();
        EmbedHttpClient http = mock(EmbedHttpClient.class);
        when(http.isHealthy()).thenReturn(true);
        EmbedServiceExecutionBackend backend = backend(pb -> p, http);

        backend.ensureStarted();
        backend.scheduledIdleShutdown();

        verify(p, never()).destroy();
        assertThat(backend.isRunning()).isTrue();
    }

    private EmbedServiceExecutionBackend backend(ProcessStarter starter, EmbedHttpClient http) {
        return new EmbedServiceExecutionBackend(props, clock, starter, http);
    }

    private Process aliveProcess() {
        Process p = mock(Process.class);
        when(p.isAlive()).thenReturn(true);
        when(p.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return p;
    }

    private EmbedHttpClient noHttp() {
        return mock(EmbedHttpClient.class);
    }

    private EmbedHttpClient http(boolean healthy, String output) {
        EmbedHttpClient http = mock(EmbedHttpClient.class);
        when(http.isHealthy()).thenReturn(healthy);
        when(http.embed(anyString())).thenReturn(new BackendResult(true, output));
        return http;
    }

    private boolean healthy() {
        return true;
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