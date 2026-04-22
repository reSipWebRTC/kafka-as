package com.kafkaasr.translation;

import com.kafkaasr.translation.events.TranslationKafkaProperties;
import com.kafkaasr.translation.pipeline.TranslationEngineProperties;
import com.kafkaasr.translation.policy.TranslationControlPlaneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    TranslationKafkaProperties.class,
    TranslationEngineProperties.class,
    TranslationControlPlaneProperties.class
})
public class TranslationWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranslationWorkerApplication.class, args);
    }
}
