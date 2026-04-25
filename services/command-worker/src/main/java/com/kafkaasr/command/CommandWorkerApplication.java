package com.kafkaasr.command;

import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.pipeline.SmartHomeClientProperties;
import com.kafkaasr.command.policy.CommandControlPlaneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    CommandKafkaProperties.class,
    SmartHomeClientProperties.class,
    CommandControlPlaneProperties.class
})
public class CommandWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommandWorkerApplication.class, args);
    }
}
