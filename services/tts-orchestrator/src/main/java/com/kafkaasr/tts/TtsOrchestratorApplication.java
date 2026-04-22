package com.kafkaasr.tts;

import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.pipeline.TtsVoicePolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({TtsKafkaProperties.class, TtsVoicePolicyProperties.class})
public class TtsOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TtsOrchestratorApplication.class, args);
    }
}
