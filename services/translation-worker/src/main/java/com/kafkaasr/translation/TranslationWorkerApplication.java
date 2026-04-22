package com.kafkaasr.translation;

import com.kafkaasr.translation.events.TranslationKafkaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TranslationKafkaProperties.class)
public class TranslationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranslationWorkerApplication.class, args);
    }
}
