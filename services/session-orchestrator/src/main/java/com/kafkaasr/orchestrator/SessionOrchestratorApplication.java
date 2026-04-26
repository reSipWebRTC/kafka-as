package com.kafkaasr.orchestrator;

import com.kafkaasr.orchestrator.compensation.CompensationSagaProperties;
import com.kafkaasr.orchestrator.events.OrchestratorKafkaProperties;
import com.kafkaasr.orchestrator.policy.ControlPlaneClientProperties;
import com.kafkaasr.orchestrator.service.SessionOrchestrationProperties;
import com.kafkaasr.orchestrator.session.SessionStoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        OrchestratorKafkaProperties.class,
        SessionStoreProperties.class,
        ControlPlaneClientProperties.class,
        SessionOrchestrationProperties.class,
        CompensationSagaProperties.class
})
public class SessionOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SessionOrchestratorApplication.class, args);
    }
}
