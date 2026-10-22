package ai.os.controller;

import ai.os.controller.agent.Agent;
import ai.os.controller.agent.AgentKind;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiOsControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiOsControllerApplication.class, args);
    }

    @Bean
    Map<AgentKind, Agent> agentRegistry(List<Agent> agents) {
        return agents.stream()
                .collect(Collectors.toMap(Agent::kind, Function.identity()));
    }
}