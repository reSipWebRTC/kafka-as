package com.kafkaasr.translation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "translation.kafka.enabled=false")
class TranslationWorkerApplicationTests {

    @Test
    void contextLoads() {
    }
}
