package com.kafkaasr.asr.kafka;

import com.kafkaasr.asr.events.AsrPartialEvent;
import reactor.core.publisher.Mono;

public interface AsrPartialPublisher {

    Mono<Void> publish(AsrPartialEvent event);
}
