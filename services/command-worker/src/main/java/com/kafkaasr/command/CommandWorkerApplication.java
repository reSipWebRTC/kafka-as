package com.kafkaasr.command;

import com.kafkaasr.command.events.CommandKafkaProperties;
import com.kafkaasr.command.state.CommandStateStoreProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    CommandKafkaProperties.class,
    CommandStateStoreProperties.class
})
public class CommandWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommandWorkerApplication.class, args);
    }
}
