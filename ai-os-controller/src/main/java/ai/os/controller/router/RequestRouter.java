package ai.os.controller.router;

public interface RequestRouter {

    TaskType classify(String prompt);
}