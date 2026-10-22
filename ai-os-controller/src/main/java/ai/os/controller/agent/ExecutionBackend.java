package ai.os.controller.agent;

public interface ExecutionBackend {

    BackendResult invoke(String modelId, String instruction);
}