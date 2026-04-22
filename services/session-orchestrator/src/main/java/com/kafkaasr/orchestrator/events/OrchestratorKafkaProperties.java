package com.kafkaasr.orchestrator.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "orchestrator.kafka")
public class OrchestratorKafkaProperties {

    @NotBlank
    private String sessionControlTopic = "session.control";

    @NotBlank
    private String producerId = "session-orchestrator";

    public String getSessionControlTopic() {
        return sessionControlTopic;
    }

    public void setSessionControlTopic(String sessionControlTopic) {
        this.sessionControlTopic = sessionControlTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }
}
