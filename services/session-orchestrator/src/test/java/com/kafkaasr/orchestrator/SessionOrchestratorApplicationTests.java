package com.kafkaasr.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "orchestrator.kafka.enabled=false",
        "orchestrator.session-orchestration.enabled=false",
        "orchestrator.session-orchestration.aggregation-enabled=false"
})
class SessionOrchestratorApplicationTests {

    @Test
    void contextLoads() {
    }
}
