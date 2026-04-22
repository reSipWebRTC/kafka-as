package com.kafkaasr.translation.kafka;

import com.kafkaasr.translation.events.TranslationResultEvent;
import reactor.core.publisher.Mono;

public interface TranslationResultPublisher {

    Mono<Void> publish(TranslationResultEvent event);
}
