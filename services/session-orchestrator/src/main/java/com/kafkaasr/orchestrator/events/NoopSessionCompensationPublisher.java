package com.kafkaasr.orchestrator.events;

import com.kafkaasr.orchestrator.session.SessionState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orchestrator.kafka.enabled", havingValue = "false")
public class NoopSessionCompensationPublisher implements SessionCompensationPublisher {

    @Override
    public void publishTimeoutClose(String timeoutType, String outcome, SessionState state, Throwable failure) {
        // No-op when Kafka is disabled in local/unit contexts.
    }
}
