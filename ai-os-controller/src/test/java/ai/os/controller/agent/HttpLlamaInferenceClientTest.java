package ai.os.controller.agent;

import ai.os.controller.config.LlamaProperties;
import ai.os.controller.modelmanager.ModelManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpLlamaInferenceClientTest {

    private ModelManager modelManager;
    private LlamaProperties llamaProperties;
    private HttpClient httpClient;
    private MetricsCollector metricsCollector;
    private HttpLlamaInferenceClient client;

    @SuppressWarnings("unchecked")
    private final HttpResponse<InputStream> httpResponse = mock(HttpResponse.class);

    @BeforeEach
    void setUp() throws Exception {
        modelManager = mock(ModelManager.class);
        llamaProperties = new LlamaProperties();
        llamaProperties.setPort(9999);
        httpClient = mock(HttpClient.class);
        metricsCollector = new MetricsCollector();
        metricsCollector.begin();
        client = new HttpLlamaInferenceClient(modelManager, llamaProperties, metricsCollector, httpClient, new ObjectMapper());

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" from the model!\"}}]}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream())))
                .thenReturn(httpResponse);
    }

    private static InputStream sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            sb.append(line).append("\n\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void completeLoadsModelThenStreamsChatCompletion() throws Exception {
        String result = client.complete("What is 2+2?", "coding-small");

        verify(modelManager).load("coding-small");
        verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(reqCaptor.capture(), any());
        HttpRequest sent = reqCaptor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("http://127.0.0.1:9999/v1/chat/completions");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.bodyPublisher()).isPresent();

        assertThat(result).isEqualTo("Hello from the model!");

        MetricsCollector.RequestMetrics metrics = metricsCollector.snapshot();
        assertThat(metrics.getTokens()).isEqualTo(2);
        assertThat(metrics.getFirstTokenMs()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.getInferenceMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void completeThrowsWhenModelManagerFails() throws Exception {
        doThrow(new IllegalStateException("model not found")).when(modelManager).load("bad-model");

        assertThatThrownBy(() -> client.complete("hi", "bad-model"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("Failed to load model")
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void completeThrowsOnNon200Response() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn(
                new ByteArrayInputStream("Internal Server Error".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void completeThrowsOnEmptyStreamingCompletion() throws Exception {
        when(httpResponse.body()).thenReturn(sse(
                "data: {\"choices\":[{\"delta\":{}}]}",
                "data: [DONE]"));

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("empty streaming completion");
    }

    @Test
    void completeThrowsWhenStreamEndsWithoutDone() throws Exception {
        when(httpResponse.body()).thenReturn(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"));

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("stream ended without [DONE]");
    }

    @Test
    void completeThrowsWhenGenerationExceedsDeadline() throws Exception {
        llamaProperties.setRequestTimeout(Duration.ofMillis(200));
        when(httpResponse.body()).thenReturn(new InputStream() {
            final byte[] head = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            int position;
            volatile boolean closed;

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public int read() throws IOException {
                if (position < head.length) {
                    return head[position++] & 0xFF;
                }
                while (!closed) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
                throw new IOException("stream closed by deadline");
            }
        });

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("did not finish within");
    }

    @Test
    void completeThrowsOnTransportError() throws Exception {
        doThrow(new IOException("connection refused"))
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("llama-server request failed")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void completeRetriesOnceAfterTransportFailure() throws Exception {
        doThrow(new IOException("connection refused"))
                .doReturn(httpResponse)
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));

        String result = client.complete("What is 2+2?", "coding-small");

        assertThat(result).isEqualTo("Hello from the model!");
        verify(modelManager, org.mockito.Mockito.times(2)).load("coding-small");
        verify(httpClient, org.mockito.Mockito.times(2))
                .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));
    }

    @Test
    void completeReloadsModelBeforeRetryAfterTransportFailure() throws Exception {
        doThrow(new IOException("connection refused"))
                .doReturn(httpResponse)
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));

        client.complete("hi", "coding-small");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(modelManager, httpClient);
        inOrder.verify(modelManager).load("coding-small");
        inOrder.verify(httpClient).send(any(HttpRequest.class), any());
        inOrder.verify(modelManager).load("coding-small");
        inOrder.verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    void completeThrowsAfterRetryWhenServerStaysDown() throws Exception {
        doThrow(new IOException("connection refused"))
                .doThrow(new IOException("connection refused"))
                .when(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("llama-server request failed")
                .satisfies(t -> assertThat(((LlamaInferenceException) t).isTransportUnavailable()).isTrue());
        verify(modelManager, org.mockito.Mockito.times(2)).load("coding-small");
    }

    @Test
    void completeDoesNotRetryOnNonTransportFailure() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn(
                new ByteArrayInputStream("Internal Server Error".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.complete("hi", "coding-small"))
                .isInstanceOf(LlamaInferenceException.class)
                .hasMessageContaining("HTTP 500");

        verify(modelManager, org.mockito.Mockito.times(1)).load("coding-small");
        verify(httpClient, org.mockito.Mockito.times(1))
                .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()));
    }

    @Test
    void completeRejectsBlankPrompt() {
        assertThatThrownBy(() -> client.complete("  ", "coding-small"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt must not be blank");
    }

    @Test
    void completeRejectsBlankModelId() {
        assertThatThrownBy(() -> client.complete("hello", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId must not be blank");
    }
}