package ai.os.controller.planner;

import ai.os.controller.router.TaskType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SimpleTaskPlanner implements TaskPlanner {

    @Override
    public List<Step> plan(TaskType type, String prompt) {
        return List.of(
                new Step(1, type, "Clarify task intent"),
                new Step(2, type, "Resolve context for %s task".formatted(type.name().toLowerCase())),
                new Step(3, type, "Execute task through a coding agent"));
    }
}