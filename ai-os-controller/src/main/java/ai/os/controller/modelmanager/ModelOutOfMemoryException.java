package ai.os.controller.modelmanager;

public class ModelOutOfMemoryException extends RuntimeException {

    public ModelOutOfMemoryException(String message) {
        super(message);
    }
}