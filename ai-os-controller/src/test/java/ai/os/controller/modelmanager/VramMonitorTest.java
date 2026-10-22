package ai.os.controller.modelmanager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VramMonitorTest {

    @Test
    void parsesJsonOutputInBytes() throws Exception {
        String json = """
                {
                  "card0": {
                    "VRAM Total Memory (B)": "4294967296",
                    "VRAM Total Used Memory (B)": "1073741824"
                  }
                }
                """;

        VramMonitor.VramSnapshot snapshot = VramMonitor.parseJson(json);

        assertThat(snapshot.totalMb).isEqualTo(4096);
        assertThat(snapshot.usedMb).isEqualTo(1024);
    }

    @Test
    void parsesJsonOutputInMiB() throws Exception {
        String json = """
                {
                  "card0": {
                    "VRAM Total Memory (MiB)": "2048",
                    "VRAM Used Memory (MiB)": "512"
                  }
                }
                """;

        VramMonitor.VramSnapshot snapshot = VramMonitor.parseJson(json);

        assertThat(snapshot.totalMb).isEqualTo(2048);
        assertThat(snapshot.usedMb).isEqualTo(512);
    }

    @Test
    void aggregatesVramAcrossMultipleGpus() throws Exception {
        String json = """
                {
                  "card0": {
                    "VRAM Total Memory (MiB)": "2048",
                    "VRAM Total Used Memory (MiB)": "512"
                  },
                  "card1": {
                    "VRAM Total Memory (MiB)": "2048",
                    "VRAM Total Used Memory (MiB)": "768"
                  }
                }
                """;

        VramMonitor.VramSnapshot snapshot = VramMonitor.parseJson(json);

        assertThat(snapshot.totalMb).isEqualTo(4096);
        assertThat(snapshot.usedMb).isEqualTo(1280);
    }

    @Test
    void parsesTextOutput() {
        String text = """
                ======================= ROCm System Management Interface =======================
                ================================ Concise Info ==================================
                GPU[0]\t\t: gfx1031
                GPU[0]\t\t: AMD Radeon RX 6500M
                ================================== GPU Memory ==================================
                GPU[0]\t\t: "VRAM Total Memory (B)" 4294967296
                GPU[0]\t\t: "VRAM Total Used Memory (B)" 1073741824
                """;

        VramMonitor.VramSnapshot snapshot = VramMonitor.parseText(text);

        assertThat(snapshot.totalMb).isEqualTo(4096);
        assertThat(snapshot.usedMb).isEqualTo(1024);
    }

    @Test
    void parsesTextOutputWithMiBUnits() {
        String text = """
                GPU[0]\t\t: "VRAM Total Memory (MiB)" 4096
                GPU[0]\t\t: "VRAM Total Used Memory (MiB)" 896
                """;

        VramMonitor.VramSnapshot snapshot = VramMonitor.parseText(text);

        assertThat(snapshot.totalMb).isEqualTo(4096);
        assertThat(snapshot.usedMb).isEqualTo(896);
    }

    @Test
    void parsesNestedJsonStructure() throws Exception {
        String json = """
                {
                  "command": "rocm-smi --showmeminfo vram",
                  "version": "6.3.0",
                  "cards": [
                    {
                      "index": 0,
                      "vram": {
                        "VRAM Total Memory (MiB)": "4096",
                        "VRAM Used Memory (MiB)": "128"
                      }
                    }
                  ]
                }
                """;

        VramMonitor.VramSnapshot snapshot = VramMonitor.parseJson(json);

        assertThat(snapshot.totalMb).isEqualTo(4096);
        assertThat(snapshot.usedMb).isEqualTo(128);
    }

    @Test
    void jsonWithoutVramMetricsReturnsNull() throws Exception {
        assertThat(VramMonitor.parseJson("""
                {"cards": [{"index": 0, "name": "card0"}]}
                """)).isNull();
    }

    @Test
    void invalidJsonThrows() {
        assertThatThrownBy(() -> VramMonitor.parseJson("not json at all"))
                .isInstanceOf(Exception.class);
    }
}