package com.kafkaasr.tts.kafka;

import com.kafkaasr.tts.events.TtsReadyEvent;
import reactor.core.publisher.Mono;

public interface TtsReadyPublisher {

    Mono<Void> publish(TtsReadyEvent event);
}
