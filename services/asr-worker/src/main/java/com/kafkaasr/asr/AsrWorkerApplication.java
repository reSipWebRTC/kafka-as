package com.kafkaasr.asr;

import com.kafkaasr.asr.events.AsrKafkaProperties;
import com.kafkaasr.asr.pipeline.AsrInferenceProperties;
import com.kafkaasr.asr.policy.AsrControlPlaneProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AsrKafkaProperties.class, AsrInferenceProperties.class, AsrControlPlaneProperties.class})
public class AsrWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AsrWorkerApplication.class, args);
    }
}
