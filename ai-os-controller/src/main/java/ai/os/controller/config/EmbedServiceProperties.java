package ai.os.controller.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-os.embed")
public class EmbedServiceProperties {

    private int port = 8100;
    private String runScript = "../python-services/embed-service/run.sh";
    private Duration startTimeout = Duration.ofSeconds(60);
    private Duration idleTimeout = Duration.ofMinutes(5);
    private Duration idleCheckInterval = Duration.ofSeconds(10);

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getRunScript() {
        return runScript;
    }

    public void setRunScript(String runScript) {
        this.runScript = runScript;
    }

    public Duration getStartTimeout() {
        return startTimeout;
    }

    public void setStartTimeout(Duration startTimeout) {
        this.startTimeout = startTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getIdleCheckInterval() {
        return idleCheckInterval;
    }

    public void setIdleCheckInterval(Duration idleCheckInterval) {
        this.idleCheckInterval = idleCheckInterval;
    }
}