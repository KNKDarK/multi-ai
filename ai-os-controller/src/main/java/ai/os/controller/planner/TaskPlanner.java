package ai.os.controller.planner;

import ai.os.controller.router.TaskType;
import java.util.List;

public interface TaskPlanner {

    List<Step> plan(TaskType type, String prompt);
}