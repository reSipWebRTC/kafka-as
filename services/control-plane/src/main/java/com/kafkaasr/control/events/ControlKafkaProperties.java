package com.kafkaasr.control.events;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "control.kafka")
public class ControlKafkaProperties {

    @NotBlank
    private String policyChangedTopic = "tenant.policy.changed";

    @NotBlank
    private String producerId = "control-plane";

    public String getPolicyChangedTopic() {
        return policyChangedTopic;
    }

    public void setPolicyChangedTopic(String policyChangedTopic) {
        this.policyChangedTopic = policyChangedTopic;
    }

    public String getProducerId() {
        return producerId;
    }

    public void setProducerId(String producerId) {
        this.producerId = producerId;
    }
}
