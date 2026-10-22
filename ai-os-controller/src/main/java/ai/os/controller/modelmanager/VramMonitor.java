package ai.os.controller.modelmanager;

import ai.os.controller.config.ModelManagerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VramMonitor {

    static final class VramSnapshot {
        final long totalMb;
        final long usedMb;

        VramSnapshot(long totalMb, long usedMb) {
            this.totalMb = totalMb;
            this.usedMb = usedMb;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(VramMonitor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VRAM_KEY = Pattern.compile("(?i)vram.*(total|used).*memory.*");
    private static final Pattern VRAM_TEXT_LINE =
            Pattern.compile("\"VRAM Total(?: Used)? Memory \\(([A-Za-z]+)\\)\"\\s+([0-9]+)");

    private final String rocmSmiBinary;
    private volatile long totalVramMb = 0;
    private volatile long usedVramMb = 0;
    private volatile boolean hasData = false;

    public VramMonitor(ModelManagerProperties properties) {
        this.rocmSmiBinary = properties.getRocmSmiBinary();
    }

    @Scheduled(fixedDelayString = "${ai-os.model-manager.vram-poll-interval:5s}",
        initialDelayString = "${ai-os.model-manager.vram-poll-interval:5s}")
    public void pollVramUsage() {
        VramSnapshot snapshot = readSnapshot();
        if (snapshot == null) {
            return;
        }
        this.totalVramMb = snapshot.totalMb;
        this.usedVramMb = snapshot.usedMb;
        this.hasData = true;
        log.info("VRAM snapshot: used={} MB, available={} MB of {} MB", usedVramMb, availableVramMb(), totalVramMb);
    }

    public long currentVramUsedMb() {
        return usedVramMb;
    }

    public long availableVramMb() {
        return totalVramMb == 0 ? 0 : Math.max(0, totalVramMb - usedVramMb);
    }

    public boolean hasData() {
        return hasData;
    }

    private VramSnapshot readSnapshot() {
        try {
            String json = runRocmSmi("--showmeminfo", "vram", "--json");
            VramSnapshot jsonSnapshot = parseJson(json);
            if (jsonSnapshot != null) {
                return jsonSnapshot;
            }
            log.debug("rocm-smi --json produced no vram metrics; falling back to text output");
        } catch (Exception jsonFailure) {
            log.debug("rocm-smi --json unsupported ({}); falling back to text output", jsonFailure.getMessage());
        }
        try {
            String text = runRocmSmi("--showmeminfo", "vram");
            VramSnapshot textSnapshot = parseText(text);
            if (textSnapshot == null) {
                log.warn("Could not parse rocm-smi output");
            }
            return textSnapshot;
        } catch (Exception textFailure) {
            log.warn("rocm-smi unavailable ({}): {}", rocmSmiBinary, textFailure.getMessage());
            return null;
        }
    }

    private String runRocmSmi(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(rocmSmiBinary);
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(rocmSmiBinary + " exited with code " + exit);
        }
        return output.toString();
    }

    static VramSnapshot parseJson(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !(root.isObject() || root.isArray())) {
            return null;
        }
        Accumulator acc = new Accumulator();
        walk(root, acc);
        return acc.found ? new VramSnapshot(acc.totalMb, acc.usedMb) : null;
    }

    static VramSnapshot parseText(String output) {
        Accumulator acc = new Accumulator();
        for (String line : output.split("\\r?\\n")) {
            Matcher matcher = VRAM_TEXT_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            long value = parseLong(matcher.group(2));
            if (value < 0) {
                continue;
            }
            long mb = toMb(matcher.group(1), value);
            if (mb < 0) {
                continue;
            }
            acc.found = true;
            if (line.contains("Used")) {
                acc.usedMb += mb;
            } else {
                acc.totalMb += mb;
            }
        }
        return acc.found ? new VramSnapshot(acc.totalMb, acc.usedMb) : null;
    }

    private static final class Accumulator {
        boolean found;
        long totalMb;
        long usedMb;
    }

    private static void walk(JsonNode node, Accumulator acc) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (VRAM_KEY.matcher(key).matches()) {
                    long value = parseValue(field.getValue());
                    long mb = value >= 0 ? toMb(extractUnit(key), value) : -1;
                    if (mb >= 0) {
                        acc.found = true;
                        if (key.toLowerCase(Locale.ROOT).contains("used")) {
                            acc.usedMb += mb;
                        } else {
                            acc.totalMb += mb;
                        }
                    }
                }
                walk(field.getValue(), acc);
            }
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, acc));
        }
    }

    private static long parseValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return -1;
        }
        if (node.isNumber() || node.canConvertToExactIntegral()) {
            return node.asLong();
        }
        return parseLong(node.asText());
    }

    private static long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String extractUnit(String key) {
        int open = key.lastIndexOf('(');
        int close = key.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return key.substring(open + 1, close);
        }
        return "";
    }

    private static long toMb(String unit, long value) {
        return switch (unit.toUpperCase(Locale.ROOT)) {
            case "B" -> value / (1024L * 1024L);
            case "KB" -> value / 1024L;
            case "MB", "MIB" -> value;
            case "GB", "GIB" -> value * 1024L;
            default -> -1;
        };
    }
}