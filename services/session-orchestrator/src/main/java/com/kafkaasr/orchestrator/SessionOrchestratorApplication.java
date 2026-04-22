package com.kafkaasr.orchestrator;

import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OrchestratorKafkaProperties.class)
public class SessionOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SessionOrchestratorApplication.class, args);
    }
}
