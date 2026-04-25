package com.kafkaasr.translation.kafka;

import com.kafkaasr.translation.events.TranslationRequestEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "translation.kafka.enabled", havingValue = "false")
public class NoopTranslationRequestPublisher implements TranslationRequestPublisher {

    @Override
    public Mono<Void> publish(TranslationRequestEvent event) {
        return Mono.empty();
    }
}
