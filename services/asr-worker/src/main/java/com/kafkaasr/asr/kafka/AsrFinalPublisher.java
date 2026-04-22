package com.kafkaasr.asr.kafka;

import com.kafkaasr.asr.events.AsrFinalEvent;
import reactor.core.publisher.Mono;

public interface AsrFinalPublisher {

    Mono<Void> publish(AsrFinalEvent event);
}
