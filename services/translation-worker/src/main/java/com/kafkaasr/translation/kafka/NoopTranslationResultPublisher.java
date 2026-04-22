package com.kafkaasr.translation.kafka;

import com.kafkaasr.translation.events.TranslationResultEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "translation.kafka.enabled", havingValue = "false")
public class NoopTranslationResultPublisher implements TranslationResultPublisher {

    @Override
    public Mono<Void> publish(TranslationResultEvent event) {
        return Mono.empty();
    }
}
