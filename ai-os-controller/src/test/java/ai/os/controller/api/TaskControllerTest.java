package ai.os.controller.api;

import ai.os.controller.agent.LlamaInferenceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlamaInferenceClient inferenceClient;

    @BeforeEach
    void stubInferenceClient() {
        when(inferenceClient.complete(anyString(), anyString()))
                .thenReturn("public class Main { public static void main(String[] args) {} }");
    }

    @Test
    void postTaskRunsRouterThroughPlannerSchedulerToCodingAgent() throws Exception {
        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"write a REST controller\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("WRITING"))
                .andExpect(jsonPath("$.dispatchStatus").value("DISPATCHED"))
                .andExpect(jsonPath("$.plannedSteps").value(3))
                .andExpect(jsonPath("$.message").value(containsString("public class Main")));
    }

    @Test
    void untargetedPromptDefaultsToCoding() throws Exception {
        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"review the repository layout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("CODING"))
                .andExpect(jsonPath("$.message").value(containsString("public class Main")));
    }

    @Test
    void blankPromptIsRejectedBeforeAgentDispatch() throws Exception {
        mockMvc.perform(post("/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"   \\t\\n\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("prompt must not be blank"));

        verifyNoInteractions(inferenceClient);
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }
}
