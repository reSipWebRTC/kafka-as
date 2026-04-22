package com.kafkaasr.asr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "asr.kafka.enabled=false")
class AsrWorkerApplicationTests {

    @Test
    void contextLoads() {
    }
}
