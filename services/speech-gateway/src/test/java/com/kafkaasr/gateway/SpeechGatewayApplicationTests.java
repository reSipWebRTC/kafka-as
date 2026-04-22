package com.kafkaasr.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"gateway.kafka.enabled=false", "gateway.downlink.enabled=false"})
class SpeechGatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
