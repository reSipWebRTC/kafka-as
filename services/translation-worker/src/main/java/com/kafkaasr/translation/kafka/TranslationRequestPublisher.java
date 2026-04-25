package com.kafkaasr.translation.kafka;

import com.kafkaasr.translation.events.TranslationRequestEvent;
import reactor.core.publisher.Mono;

public interface TranslationRequestPublisher {

    Mono<Void> publish(TranslationRequestEvent event);
}
