package com.kafkaasr.command;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "command.kafka.enabled=false",
        "command.smarthome.enabled=false",
        "command.control-plane.enabled=false"
})
class CommandWorkerApplicationTests {

    @Test
    void contextLoads() {
    }
}
