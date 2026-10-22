package ai.os.controller.router;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class KeywordRequestRouter implements RequestRouter {

    private static final List<String> RESEARCH_KEYWORDS =
            List.of("research", "report", "analy", "summar", "paper", "cite", "study");
    private static final List<String> DATA_KEYWORDS =
            List.of("data", "dataset", "csv", "sql", "query", "table", "json", "statistics");
    private static final List<String> WRITING_KEYWORDS =
            List.of("write", "essay", "draft", "email", "prose", "story", "blog", "article");

    @Override
    public TaskType classify(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (matches(text, RESEARCH_KEYWORDS)) return TaskType.RESEARCH;
        if (matches(text, DATA_KEYWORDS)) return TaskType.DATA;
        if (matches(text, WRITING_KEYWORDS)) return TaskType.WRITING;
        return TaskType.CODING;
    }

    private boolean matches(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}