package com.kafkaasr.tts;

import com.kafkaasr.tts.events.TtsKafkaProperties;
import com.kafkaasr.tts.pipeline.TtsSynthesisProperties;
import com.kafkaasr.tts.pipeline.TtsVoicePolicyProperties;
import com.kafkaasr.tts.policy.TtsControlPlaneProperties;
import com.kafkaasr.tts.storage.TtsStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    TtsKafkaProperties.class,
    TtsVoicePolicyProperties.class,
    TtsSynthesisProperties.class,
    TtsControlPlaneProperties.class,
    TtsStorageProperties.class
})
public class TtsOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TtsOrchestratorApplication.class, args);
    }
}
